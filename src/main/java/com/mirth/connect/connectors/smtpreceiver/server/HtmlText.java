/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at
 * https://mozilla.org/MPL/2.0/.
 */

package com.mirth.connect.connectors.smtpreceiver.server;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A simple HTML to text conversion, without libraries. */
final class HtmlText {
    private static final Pattern ENTITY = Pattern.compile("&(#x?[0-9a-fA-F]+|[a-zA-Z]+);");

    private HtmlText() {}

    static String toText(String html) {
        String s = html;
        s = s.replaceAll("(?is)<(script|style|head)\\b.*?</\\1\\s*>", "");
        s = s.replaceAll("(?s)<!--.*?-->", "");
        s = s.replaceAll("(?i)<br\\s*/?>", "\n");
        s = s.replaceAll("(?i)<li\\b[^>]*>", "- ");
        s = s.replaceAll("(?i)</(p|div|tr|li|h[1-6]|table|ul|ol|blockquote)\\s*>", "\n");
        s = s.replaceAll("(?i)</t[dh]\\s*>", " ");
        s = s.replaceAll("(?s)<[^>]*>", "");
        s = decodeEntities(s);
        // Blank lines from the markup become at most one.
        return s.replaceAll("[ \\t]+\\n", "\n").replaceAll("\\n{3,}", "\n\n").strip();
    }

    private static String decodeEntities(String s) {
        Matcher m = ENTITY.matcher(s);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String name = m.group(1);
            String replacement = null;
            if (name.startsWith("#")) {
                try {
                    int cp = name.startsWith("#x") || name.startsWith("#X") ? Integer.parseInt(name.substring(2), 16) : Integer.parseInt(name.substring(1));
                    replacement = new String(Character.toChars(cp));
                } catch (IllegalArgumentException e) {
                    replacement = null;
                }
            } else {
                switch (name) {
                    case "amp":
                        replacement = "&";
                        break;
                    case "lt":
                        replacement = "<";
                        break;
                    case "gt":
                        replacement = ">";
                        break;
                    case "quot":
                        replacement = "\"";
                        break;
                    case "apos":
                        replacement = "'";
                        break;
                    case "nbsp":
                        replacement = " ";
                        break;
                    case "ndash":
                        replacement = "–";
                        break;
                    case "mdash":
                        replacement = "—";
                        break;
                    case "hellip":
                        replacement = "…";
                        break;
                    case "euro":
                        replacement = "€";
                        break;
                    case "copy":
                        replacement = "©";
                        break;
                    default:
                        replacement = null;
                }
            }
            m.appendReplacement(out, Matcher.quoteReplacement(replacement != null ? replacement : m.group()));
        }
        m.appendTail(out);
        return out.toString();
    }
}
