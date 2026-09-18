/*
 * Copyright (c) 2010-2026 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.caldav.internal.handler;

import java.net.URI;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.caldav.internal.client.CalDavHttpException;
import org.openhab.binding.caldav.internal.client.DavTransport;
import org.openhab.binding.caldav.internal.config.AccountConfiguration;
import org.openhab.binding.caldav.internal.config.CalDavConfiguration;
import org.openhab.binding.caldav.internal.config.CalendarConfiguration;
import org.openhab.binding.caldav.internal.logic.CalendarEventSelection;
import org.openhab.binding.caldav.internal.logic.CalendarLimitException;
import org.openhab.binding.caldav.internal.logic.CalendarWindow;
import org.openhab.binding.caldav.internal.logic.EventJson;
import org.openhab.binding.caldav.internal.model.CalendarEvent;
import org.openhab.binding.caldav.internal.sync.CalendarSynchronizer;
import org.openhab.core.i18n.TimeZoneProvider;
import org.openhab.core.library.types.DateTimeType;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.storage.Storage;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.ThingStatusInfo;
import org.openhab.core.thing.binding.BaseThingHandler;
import org.openhab.core.types.Command;
import org.openhab.core.types.RefreshType;
import org.openhab.core.types.State;
import org.openhab.core.types.UnDefType;

import com.google.gson.Gson;

/**
 * Publishes bounded calendar snapshots and updates clock-dependent channels locally.
 * 
 * @author Andreas Vilippus - Cache, lifecycle and local event transitions
 */
@NonNullByDefault
public class CalendarHandler extends BaseThingHandler {
    private final TimeZoneProvider timeZoneProvider;
    private final Storage<String> storage;
    private final Object lifecycle = new Object();
    private final Map<String, State> states = new HashMap<>();
    private @Nullable ScheduledFuture<?> clockJob;
    private long generation;
    private boolean active;
    private boolean loaded;
    private boolean synchronizedOnce;
    private List<CalendarEvent> events = List.of();
    private @Nullable CalendarSynchronizer synchronizer;
    private @Nullable DavTransport transport;
    private @Nullable CalendarWindow cachedHorizon;
    private @Nullable ZoneId cachedZone;
    private @Nullable CalendarConfiguration configuration;
    private @Nullable URI collection;
    private final Gson gson = new Gson();
    private String cacheIdentity = "";

    public CalendarHandler(Thing thing, TimeZoneProvider timeZoneProvider, Storage<String> storage) {
        super(thing);
        this.timeZoneProvider = timeZoneProvider;
        this.storage = storage;
    }

    @Override
    public void initialize() {
        synchronized (lifecycle) {
            generation++;
            active = true;
            CalendarConfiguration initial = getConfigAs(CalendarConfiguration.class);
            configuration = initial;
            if (!initial.enabled) {
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.DISABLED);
                return;
            }
            set("sync#status", new StringType("ERROR"));
            set("sync#error", new StringType("Waiting for account synchronization"));
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.BRIDGE_OFFLINE);
        }
        Bridge bridge = getBridge();
        if (bridge != null && bridge.getHandler() instanceof AccountHandler account) {
            account.requestSync();
        }
    }

    @Override
    public void bridgeStatusChanged(ThingStatusInfo info) {
        if (info.getStatus() != ThingStatus.ONLINE) {
            bridgeConnectionFailed();
        }
    }

    public void bridgeConnectionFailed() {
        synchronized (lifecycle) {
            if (!active || configuration == null || !configuration.enabled) {
                return;
            }
            set("sync#status", new StringType("ERROR"));
            set("sync#error", new StringType("Account bridge is offline"));
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.BRIDGE_OFFLINE);
        }
    }

    @Override
    public void handleCommand(ChannelUID channel, Command command) {
        if (command != RefreshType.REFRESH) {
            return;
        }
        synchronized (lifecycle) {
            if (!active) {
                return;
            }
            updateState(channel, states.getOrDefault(channel.getId(), UnDefType.UNDEF));
        }
    }

    public void synchronize(DavTransport client, AccountConfiguration account, BooleanSupplier accountValid)
            throws InterruptedException {
        long current;
        CalendarConfiguration config;
        synchronized (lifecycle) {
            if (!active || configuration == null || !configuration.enabled) {
                return;
            }
            current = generation;
            config = Objects.requireNonNull(configuration);
        }
        try {
            ZoneId zone = timeZoneProvider.getTimeZone();
            ZonedDateTime now = ZonedDateTime.now(zone);
            URI uri = CalDavConfiguration.validate(config, account);
            CalendarWindow horizon = CalDavConfiguration.horizon(account, zone, now);
            CalDavConfiguration.validate(CalendarWindow.from(config, zone, now), horizon);
            CalendarSynchronizer worker;
            synchronized (lifecycle) {
                if (!valid(current, accountValid)) {
                    return;
                }
                String identity = account.url + "|" + account.username + "|" + uri + "|" + zone;
                if (!identity.equals(cacheIdentity)) {
                    clearEvents();
                    cacheIdentity = identity;
                    restore(horizon, zone, config);
                }
                if (synchronizer == null || transport != client || !uri.equals(collection)) {
                    synchronizer = new CalendarSynchronizer(client, uri);
                    transport = client;
                    collection = uri;
                }
                worker = synchronizer;
                set("sync#status", new StringType("SYNCING"));
            }
            CalendarSynchronizer.Result result = worker.synchronize(horizon, zone, account.syncMode,
                    config.includeCancelled);
            synchronized (lifecycle) {
                if (!valid(current, accountValid)) {
                    return;
                }
                events = result.events();
                loaded = true;
                synchronizedOnce = true;
                cachedHorizon = horizon;
                cachedZone = zone;
                publish(config, zone, now);
                boolean partial = result.failedResources() > 0;
                set("sync#status", new StringType(partial ? "PARTIAL" : "OK"));
                set("sync#error", new StringType(
                        partial ? result.failedResources() + " calendar resources could not be processed" : ""));
                if (!partial) {
                    set("sync#last", new DateTimeType(now));
                }
                updateStatus(ThingStatus.ONLINE);
                save(result.snapshot());
                scheduleClock(current);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        } catch (Exception e) {
            synchronized (lifecycle) {
                if (!valid(current, accountValid)) {
                    return;
                }
                ThingStatusDetail detail = ThingStatusDetail.COMMUNICATION_ERROR;
                if (e instanceof IllegalArgumentException && !(e instanceof CalendarLimitException)) {
                    detail = ThingStatusDetail.CONFIGURATION_ERROR;
                } else if (e instanceof CalDavHttpException http) {
                    if (http.statusCode() == 401 || http.statusCode() == 403) {
                        detail = ThingStatusDetail.CONFIGURATION_ERROR;
                    }
                    if (http.statusCode() == 404) {
                        detail = synchronizedOnce ? ThingStatusDetail.GONE : ThingStatusDetail.CONFIGURATION_ERROR;
                    }
                }
                set("sync#status", new StringType("ERROR"));
                set("sync#error",
                        new StringType("Calendar synchronization failed (" + e.getClass().getSimpleName() + ")"));
                updateStatus(ThingStatus.OFFLINE, detail);
            }
        }
    }

    private boolean valid(long expected, BooleanSupplier accountValid) {
        return active && generation == expected && accountValid.getAsBoolean();
    }

    private void publish(CalendarConfiguration config, ZoneId zone, ZonedDateTime now) {
        CalendarWindow window = CalendarWindow.from(config, zone, now);
        List<CalendarEvent> sorted = CalendarEventSelection
                .sort(events.stream().filter(e -> CalendarEventSelection.overlaps(e, window, zone)).toList(), zone);
        var selection = CalendarEventSelection.select(sorted, config.maxEvents, zone);
        set("events#json", new StringType(EventJson.serialize(selection.events())));
        set("events#count", new DecimalType(selection.events().size()));
        set("events#truncated", selection.truncated() ? OnOffType.ON : OnOffType.OFF);
        set("events#range-start", new DateTimeType(window.start()));
        set("events#range-end", new DateTimeType(window.end()));
        CalendarEvent current = CalendarEventSelection.current(sorted, now.toInstant(), zone);
        set("current#active", current == null ? OnOffType.OFF : OnOffType.ON);
        publishEvent("current", current, zone);
        publishEvent("next", CalendarEventSelection.next(sorted, now.toInstant(), zone), zone);
    }

    private void publishEvent(String group, @Nullable CalendarEvent event, ZoneId zone) {
        if (event == null) {
            for (String field : List.of("uid", "title", "description", "location", "organizer", "categories", "all-day",
                    "start", "end")) {
                set(group + "#" + field, UnDefType.UNDEF);
            }
            return;
        }
        set(group + "#uid", new StringType(event.uid()));
        set(group + "#title", new StringType(event.title()));
        set(group + "#description", new StringType(event.description()));
        set(group + "#location", new StringType(event.location()));
        set(group + "#organizer", new StringType(event.organizer()));
        set(group + "#categories", new StringType(String.join(",", event.categories())));
        set(group + "#all-day", event.allDay() ? OnOffType.ON : OnOffType.OFF);
        set(group + "#start", new DateTimeType(CalendarEventSelection.start(event, zone).atZone(zone)));
        set(group + "#end", new DateTimeType(CalendarEventSelection.end(event, zone).atZone(zone)));
    }

    private void scheduleClock(long current) {
        if (clockJob != null) {
            clockJob.cancel(false);
        }
        ZoneId zone = timeZoneProvider.getTimeZone();
        Instant now = Instant.now();
        Instant next = now.plusSeconds(60);
        for (CalendarEvent event : events) {
            Instant start = CalendarEventSelection.start(event, zone), end = CalendarEventSelection.end(event, zone);
            if (start.isAfter(now) && start.isBefore(next)) {
                next = start;
            }
            if (end.isAfter(now) && end.isBefore(next)) {
                next = end;
            }
        }
        Instant midnight = now.atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant();
        if (midnight.isBefore(next)) {
            next = midnight;
        }
        long delay = Math.max(1, java.time.Duration.between(now, next).toMillis());
        clockJob = scheduler.schedule(() -> tick(current), delay, TimeUnit.MILLISECONDS);
    }

    private void tick(long current) {
        synchronized (lifecycle) {
            if (!active || generation != current || configuration == null || !loaded) {
                return;
            }
            ZoneId zone = timeZoneProvider.getTimeZone();
            if (cachedZone != null && !cachedZone.equals(zone)) {
                set("sync#status", new StringType("ERROR"));
                set("sync#error", new StringType("Time zone changed; waiting for synchronization"));
            }
            publish(Objects.requireNonNull(configuration), zone, ZonedDateTime.now(zone));
            scheduleClock(current);
        }
    }

    private void set(String channel, State state) {
        states.put(channel, state);
        updateState(channel, state);
    }

    private void clearEvents() {
        events = List.of();
        loaded = false;
        synchronizedOnce = false;
        for (String field : List.of("json", "count", "truncated", "range-start", "range-end")) {
            set("events#" + field, UnDefType.UNDEF);
        }
        set("sync#last", UnDefType.UNDEF);
        set("current#active", OnOffType.OFF);
        publishEvent("current", null, timeZoneProvider.getTimeZone());
        publishEvent("next", null, timeZoneProvider.getTimeZone());
    }

    private record Persisted(String identity, CalendarSynchronizer.Snapshot snapshot, String lastSync) {
    }

    private void save(CalendarSynchronizer.Snapshot snapshot) {
        String last = states.getOrDefault("sync#last", UnDefType.UNDEF).toString();
        storage.put(getThing().getUID().toString(), gson.toJson(new Persisted(cacheIdentity, snapshot, last)));
    }

    private void restore(CalendarWindow horizon, ZoneId zone, CalendarConfiguration config) {
        String raw = storage.get(getThing().getUID().toString());
        if (raw == null || raw.length() > 16 * 1024 * 1024) {
            return;
        }
        try {
            Persisted persisted = Objects.requireNonNull(gson.fromJson(raw, Persisted.class));
            if (!cacheIdentity.equals(persisted.identity())) {
                return;
            }
            var parsed = CalendarSynchronizer.expand(persisted.snapshot(), horizon, zone, config.includeCancelled);
            events = parsed.events();
            loaded = true;
            cachedHorizon = horizon;
            cachedZone = zone;
            if (!"UNDEF".equals(persisted.lastSync()) && !"NULL".equals(persisted.lastSync())) {
                set("sync#last", new DateTimeType(persisted.lastSync()));
            }
            publish(config, zone, ZonedDateTime.now(zone));
            scheduleClock(generation);
        } catch (RuntimeException e) {
            storage.remove(getThing().getUID().toString());
        }
    }

    @Override
    public void handleRemoval() {
        storage.remove(getThing().getUID().toString());
        super.handleRemoval();
    }

    @Override
    public void dispose() {
        synchronized (lifecycle) {
            active = false;
            generation++;
            if (clockJob != null) {
                clockJob.cancel(true);
                clockJob = null;
            }
            synchronizer = null;
            transport = null;
            configuration = null;
        }
    }
}
