/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at
 * https://mozilla.org/MPL/2.0/.
 */

package com.mirth.connect.connectors.smtpreceiver.server;

import java.util.ArrayList;
import java.util.List;

/** A mail taken apart: what the JSON and XML formats are made from. */
public final class ParsedEmail {

    public static final class Address {
        /** The display name, or null. */
        public final String name;
        public final String address;

        public Address(String name, String address) {
            this.name = name;
            this.address = address;
        }
    }

    public static final class Header {
        public final String name;
        public final String value;

        public Header(String name, String value) {
            this.name = name;
            this.value = value;
        }
    }

    public static final class Attachment {
        /** Decoded name of the file; empty when the part has none. */
        public final String filename;
        /** Media type without parameters, for example application/pdf. */
        public final String contentType;
        /** Size in bytes, after decoding. */
        public final long size;
        public final String contentId;
        /** "attachment" or "inline". */
        public final String disposition;
        /** The decoded bytes, or null when the content was left out. */
        public final byte[] content;

        public Attachment(String filename, String contentType, long size, String contentId, String disposition, byte[] content) {
            this.filename = filename;
            this.contentType = contentType;
            this.size = size;
            this.contentId = contentId;
            this.disposition = disposition;
            this.content = content;
        }
    }

    public String messageId;
    /** ISO 8601 in UTC, or null when the mail has no usable Date header. */
    public String date;
    public String subject;

    public final List<Address> from = new ArrayList<Address>();
    public final List<Address> to = new ArrayList<Address>();
    public final List<Address> cc = new ArrayList<Address>();
    public final List<Address> replyTo = new ArrayList<Address>();

    public final List<Header> headers = new ArrayList<Header>();

    /** The plain text body; when the mail only has HTML, that HTML turned into text. */
    public String text = "";
    /** The HTML body, or empty. */
    public String html = "";

    public final List<Attachment> attachments = new ArrayList<Attachment>();

    /** Set when the mail could not be taken apart. Then {@link #text} holds the mail as it was received. */
    public String parseError;
}
