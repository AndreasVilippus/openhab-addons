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

import java.io.StringReader;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.w3c.dom.Document;
import org.xml.sax.InputSource;

@NonNullByDefault
public final class CalDavXml {
    private CalDavXml() {
    }

    public static Document parse(String xml) throws Exception {
        if (xml.length() > CalDavClient.MAX_RESPONSE_BYTES) {
            throw new IllegalArgumentException("CalDAV XML response exceeds limit");
        }
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setAttribute("http://www.oracle.com/xml/jaxp/properties/maxElementDepth", "64");
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
    }
}
