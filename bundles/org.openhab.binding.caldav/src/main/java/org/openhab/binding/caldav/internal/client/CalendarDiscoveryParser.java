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

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Parses the DAV and CalDAV properties used during discovery.
 *
 * @author Andreas Vilippus - Initial contribution
 */
@NonNullByDefault
public final class CalendarDiscoveryParser {
    private static final String DAV_NAMESPACE = "DAV:";
    private static final String CALDAV_NAMESPACE = "urn:ietf:params:xml:ns:caldav";

    private CalendarDiscoveryParser() {
    }

    public static URI currentUserPrincipal(String xml, URI baseUri) throws Exception {
        return resolveHref(
                firstHref(CalDavXml.parse(xml).getElementsByTagNameNS(DAV_NAMESPACE, "current-user-principal"),
                        DAV_NAMESPACE, "href"),
                baseUri);
    }

    public static URI calendarHome(String xml, URI baseUri) throws Exception {
        return resolveHref(firstHref(CalDavXml.parse(xml).getElementsByTagNameNS(CALDAV_NAMESPACE, "calendar-home-set"),
                DAV_NAMESPACE, "href"), baseUri);
    }

    public static List<CalendarCollection> collections(String xml, URI baseUri) throws Exception {
        var document = CalDavXml.parse(xml);
        NodeList responses = document.getElementsByTagNameNS(DAV_NAMESPACE, "response");
        List<CalendarCollection> collections = new ArrayList<>();
        for (int index = 0; index < responses.getLength(); index++) {
            Element response = (Element) responses.item(index);
            String href = firstHref(response.getElementsByTagNameNS(DAV_NAMESPACE, "href"), DAV_NAMESPACE, "href");
            NodeList resourceTypes = response.getElementsByTagNameNS(DAV_NAMESPACE, "resourcetype");
            boolean calendar = false;
            for (int typeIndex = 0; typeIndex < resourceTypes.getLength(); typeIndex++) {
                if (((Element) resourceTypes.item(typeIndex)).getElementsByTagNameNS(CALDAV_NAMESPACE, "calendar")
                        .getLength() > 0) {
                    calendar = true;
                    break;
                }
            }
            if (calendar) {
                String name = firstText(response.getElementsByTagNameNS(DAV_NAMESPACE, "displayname"));
                collections.add(new CalendarCollection(resolveHref(href, baseUri), name.isBlank() ? href : name));
            }
        }
        return List.copyOf(collections);
    }

    private static String firstHref(NodeList nodes, String namespace, String localName) throws Exception {
        if (nodes.getLength() == 0) {
            throw new IllegalArgumentException("CalDAV response does not contain " + localName);
        }
        Node node = nodes.item(0);
        if (namespace.equals(node.getNamespaceURI()) && localName.equals(node.getLocalName())) {
            return node.getTextContent().trim();
        }
        NodeList hrefs = ((Element) node).getElementsByTagNameNS(namespace, localName);
        if (hrefs.getLength() == 0) {
            throw new IllegalArgumentException("CalDAV response does not contain " + localName);
        }
        return hrefs.item(0).getTextContent().trim();
    }

    private static String firstText(NodeList nodes) {
        return nodes.getLength() == 0 ? "" : nodes.item(0).getTextContent().trim();
    }

    private static URI resolveHref(String href, URI baseUri) {
        return CalDavUris.resolve(CalDavUris.validate(baseUri), href);
    }
}
