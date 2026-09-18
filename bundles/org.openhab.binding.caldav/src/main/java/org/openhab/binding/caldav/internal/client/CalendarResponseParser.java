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
package org.openhab.binding.caldav.internal.client;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.binding.caldav.internal.logic.ICalendarParser;
import org.openhab.binding.caldav.internal.model.CalendarEvent;
import org.w3c.dom.NodeList;

@NonNullByDefault
public final class CalendarResponseParser {
    private CalendarResponseParser() {
    }

    public static List<CalendarEvent> parse(String xml) throws Exception {
        var document = CalDavXml.parse(xml);
        NodeList data = document.getElementsByTagNameNS("urn:ietf:params:xml:ns:caldav", "calendar-data");
        List<CalendarEvent> events = new ArrayList<>();
        for (int index = 0; index < data.getLength(); index++) {
            events.addAll(ICalendarParser.parse(data.item(index).getTextContent()));
        }
        return List.copyOf(events);
    }
}
