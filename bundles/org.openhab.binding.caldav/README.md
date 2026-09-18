# CalDAV Binding

The CalDAV binding connects openHAB directly to CalDAV servers. An account is represented by a bridge and each CalDAV Calendar Collection by a Calendar Thing.

The binding works independently and does not require another calendar binding.

This binding is under development. The limitations below describe the current implementation; configuration options exposed in the UI do not yet all affect runtime behavior.

## Supported Things

### CalDAV Account

The `account` bridge represents a CalDAV server account. It owns authentication, server discovery, Calendar Home discovery, connection handling and synchronization scheduling.

### CalDAV Calendar

The `calendar` thing represents one CalDAV Calendar Collection below an `account` bridge.

## Discovery

After configuring the account bridge, start a CalDAV scan from the Inbox to discover Calendar Collections. Background discovery is disabled. Things can also be configured manually.

With `discoveryMode=AUTO`, the scan resolves the current user principal and Calendar Home before listing collections. With `DIRECT`, it lists collections at `calendarHome`, or at `url` if `calendarHome` is empty. The discovered collection URI is stored as `calendarUid` and used to derive the Thing ID; the display name is not used as the identity.

The account's periodic connection check always resolves the principal and Calendar Home, even in `DIRECT` mode. Manually configured calendars therefore still require an account endpoint that supports this discovery sequence.

## Time Range

Each Calendar Thing exposes events from a freely configurable time range.

Example: today plus six days:

```text
rangeAnchor = TODAY
rangeStartOffset = 0
rangeEndOffset = 6
```

This exposes seven calendar days in total.

Example: yesterday through tomorrow:

```text
rangeAnchor = TODAY
rangeStartOffset = -1
rangeEndOffset = 1
```

Internally the binding uses a half-open interval `[startInclusive, endExclusive)`.

An event is included if:

```text
event.start < range.end
AND
event.end > range.start
```

## Synchronization

The account bridge polls at `refreshInterval` (default 300 seconds, minimum 30 seconds) and coordinates updates for its Calendar Things. Each update performs a full `calendar-query` REPORT for the configured time range. Sync-token and ETag-based incremental synchronization are not implemented; changing `syncMode` has no effect.

A successful calendar update publishes `sync#status=OK` and updates `sync#last`. A failed calendar update publishes `ERROR` and an error description, while leaving previously published event states unchanged. If the account connection check fails, the calendars are marked `BRIDGE_OFFLINE`, but their sync channels are not updated. Consequently, `sync#status=OK` alone does not prove that cached event data is still current. There is no persistent event cache or `PARTIAL` status handling.

Channel commands, including `REFRESH`, are currently ignored.

## Recurring Events

Recurrence support is currently limited:

- Timed events support basic `DAILY` and `WEEKLY` rules with `INTERVAL` and `COUNT`, capped at 1000 generated occurrences per rule before range filtering.
- `UNTIL`, `BYDAY`, other rule modifiers, and other frequencies are not implemented. Unsupported modifiers can produce incorrect occurrences.
- `RDATE` is expanded only when there is no `RRULE`. Exclusion matching is limited and does not normalize all `EXDATE` representations.
- All-day recurrence expansion and reconciliation of `RECURRENCE-ID` exceptions with their master event are not implemented.
- Cancelled events are not filtered; `includeCancelled` currently has no effect.

Do not rely on this implementation for complete recurring-calendar results.

## Channels

All Calendar Thing channels are organized in channel groups.

### `events`

| Channel | Item Type | Description |
|---|---|---|
| `events#json` | String | Parsed event instances overlapping the range, sorted and limited by `maxEvents` |
| `events#count` | Number | Number of instances actually published in `events#json` |
| `events#range-start` | DateTime | Defined in metadata, but not currently updated |
| `events#range-end` | DateTime | Defined in metadata, but not currently updated |
| `events#truncated` | Switch | Indicates that additional event instances were omitted because `maxEvents` was reached |

### `current`

| Channel | Item Type |
|---|---|
| `current#active` | Switch |
| `current#uid` | String |
| `current#title` | String |
| `current#description` | String |
| `current#location` | String |
| `current#start` | DateTime |
| `current#end` | DateTime |
| `current#all-day` | Switch |
| `current#organizer` | String |
| `current#categories` | String |

### `next`

The `next` group exposes the same event fields as `current`, except that it has no `active` channel.

Currently, `current` selects the first running timed event from the published list. All-day events are not recognized as current. `next` selects the first event that is not current, which can be a past or all-day event rather than the next future event. Selection is recalculated on synchronization, not at event boundaries. Start/end channels are not cleared when no event is selected and are not populated for all-day events; they can therefore retain old values.

### `sync`

| Channel | Item Type |
|---|---|
| `sync#last` | DateTime |
| `sync#status` | String |
| `sync#error` | String |

## `events#json` Format

Example:

```json
[
  {
    "instanceId": "event-123|2026-09-18T06:00+02:00",
    "uid": "event-123",
    "recurrenceId": null,
    "title": "Waste collection",
    "description": "",
    "location": "",
    "start": "2026-09-18T06:00+02:00",
    "end": "2026-09-18T07:00+02:00",
    "allDay": false,
    "status": "CONFIRMED",
    "categories": ["Waste"],
    "organizer": ""
  }
]
```

For all-day events, `start` and `end` use `YYYY-MM-DD`; the `end` date is exclusive. A successful update with no matching events publishes `[]`.

Treat `instanceId` as an opaque identifier. `maxEvents` limits the published list after sorting; `events#truncated` indicates that additional parsed events were omitted. It does not detect missing instances caused by unsupported recurrence rules. The JSON serializer currently escapes quotes, backslashes and newlines, but not all control characters; unusual event text can produce invalid JSON.

## Configuration

### Account Parameters

| Parameter | Default | Current behavior |
|---|---|---|
| `url` | Required | Account endpoint used for principal and Calendar Home discovery |
| `username` | Required | HTTP Basic username |
| `password` | Required | HTTP Basic password |
| `requestTimeout` | `30` | Request timeout in seconds; metadata range 1–300 |
| `refreshInterval` | `300` | Polling delay in seconds; minimum 30 |
| `discoveryMode` | `AUTO` | Inbox scan mode: `AUTO` or `DIRECT`; does not change periodic account checks |
| `calendarHome` | Empty | Optional collection-listing URL for `DIRECT` scans |
| `authType` | `AUTO` | Not evaluated; only Basic authentication is implemented, including when `DIGEST` is selected |
| `verifyCertificate` | `true` | Not evaluated; certificate verification remains enabled |
| `syncMode` | `AUTO` | Not evaluated; only full calendar queries are implemented |
| `maxPastDays` | `30` | Not enforced |
| `maxFutureDays` | `365` | Not enforced |
| `readOnly` | `true` | Not evaluated; writes are not implemented |

### Calendar Parameters

| Parameter | Default | Current behavior |
|---|---|---|
| `path` | Required | Complete Calendar Collection URL |
| `calendarId` | Required | Collection identifier in metadata; not used to build requests |
| `enabled` | `true` | Enables synchronization |
| `rangeAnchor` | `TODAY` | `TODAY` (local midnight) or `NOW` |
| `rangeStartOffset` | `0` | Inclusive start offset in days |
| `rangeEndOffset` | `6` | Inclusive final-day offset; exclusive end adds one day |
| `maxEvents` | `500` | Maximum number of published instances; minimum 1 |
| `includeCancelled` | `false` | Not evaluated; cancelled instances can still be published |

The following examples configure an account bridge and one calendar. Replace the
server URL and credentials with values for the CalDAV service.

### YAML

Save this example under `$OPENHAB_CONF/yaml/`, for example as `caldav.yaml`. See the [openHAB YAML configuration documentation](https://www.openhab.org/docs/configuration/yaml/).

```yaml
version: 1
things:
  caldav:account:ionos:
    isBridge: true
    label: CalDAV Account
    config:
      url: "https://caldav.example.net/caldav/"
      username: "user@example.net"
      password: "SECRET"
      discoveryMode: AUTO
      refreshInterval: 300
      requestTimeout: 30

  caldav:calendar:ionos:family:
    bridge: caldav:account:ionos
    label: Family Calendar
    config:
      path: "https://caldav.example.net/caldav/family/"
      calendarId: "family"
      enabled: true
      rangeAnchor: TODAY
      rangeStartOffset: 0
      rangeEndOffset: 6
      maxEvents: 500
```

### Classic `.things` and `.items`

```text
Bridge caldav:account:ionos "CalDAV Account" [
    url="https://caldav.example.net/caldav/",
    username="user@example.net",
    password="SECRET",
    discoveryMode="AUTO",
    refreshInterval=300,
    requestTimeout=30
] {
    Thing calendar family "Family Calendar" [
        path="https://caldav.example.net/caldav/family/",
        calendarId="family",
        enabled=true,
        rangeAnchor="TODAY",
        rangeStartOffset=0,
        rangeEndOffset=6,
        maxEvents=500
    ]
}
```

```text
String   Family_Cal_Events     "Calendar events [%s]" { channel="caldav:calendar:ionos:family:events#json" }
Number   Family_Cal_Count       "Event count [%d]"      { channel="caldav:calendar:ionos:family:events#count" }
Switch   Family_Cal_Truncated   "Event list truncated [%s]" { channel="caldav:calendar:ionos:family:events#truncated" }
DateTime Family_Cal_LastSync    "Last sync [%1$tF %1$tR]" { channel="caldav:calendar:ionos:family:sync#last" }
String   Family_Cal_SyncStatus  "Sync [%s]"             { channel="caldav:calendar:ionos:family:sync#status" }
```

### IONOS CalDAV

IONOS Mail Business provides the individual calendar URL in Webmail under the calendar's properties. Copy that complete URL into the Calendar Thing's `path`, and use your full email address as the username. See the [IONOS CalDAV instructions](https://www.ionos.com/help/email/managing-mail-business/syncing-mail-business-calendar-with-mac-os-x/).

Configure the account `url` with an endpoint supporting principal and Calendar Home discovery, as described above. Do not construct a collection URL from its display name or `calendarId`. The `calendarId` parameter is required by the metadata but does not replace `path` in requests.

## Security

- Use HTTPS for account and calendar URLs. The client sends HTTP Basic credentials with each request; it does not enforce HTTPS itself.
- TLS certificates are validated by the Java HTTP client. `verifyCertificate=false` currently has no effect.
- HTTP redirects are not followed. Server-provided discovery URLs are resolved without a same-origin check, so use a trusted CalDAV server.
- XML external entities, external DTDs and XInclude are disabled.
- HTTP response sizes and discovered resource counts are not explicitly bounded. `maxEvents` limits published events, not downloaded data.
- The binding performs no calendar writes, regardless of `readOnly`.

## Time Zones and All-Day Events

The time range is calculated in the openHAB JVM's default time zone. `TODAY` uses local midnight, while `NOW` uses the current time. Both apply offsets in days, and the exclusive end is the anchor plus `rangeEndOffset + 1` days. Configure the end offset at least as large as the start offset.

The parser handles UTC timestamps and numeric offsets, but does not interpret `TZID` or `VTIMEZONE`. Floating timestamps and local timestamps with `TZID` are currently treated as UTC, which can shift displayed times. All-day dates remain date-only values in `events#json`. Events without an explicit end are not assigned a default duration and are excluded by the range filter.

## Troubleshooting

### Authentication failed

Check the server URL, username and password/application password.

### Calendar not found

Verify the Calendar Collection path or run discovery again.

### TLS certificate error

Fix the certificate trust chain rather than disabling certificate validation whenever possible.

## Languages

Initial UI languages:

- English
- German

English is the source/default language. Technical IDs remain language-neutral.

## Development Notes

Core metadata is located under:

```text
src/main/resources/OH-INF/
```

The separate MainUI agenda widget consumes `events#json` through a linked String Item. It is not installed with the binding. Widget installation and presentation are independent of the binding configuration.
