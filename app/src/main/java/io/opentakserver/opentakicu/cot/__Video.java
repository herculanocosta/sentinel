package io.opentakserver.opentakicu.cot;

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;

public class __Video {
    private String url;
    private String uid;
    private String sender;
    private String alias;
    private ConnectionEntry ConnectionEntry;

    public __Video(String url, String uid, ConnectionEntry ConnectionEntry) {
        this.url = url;
        this.uid = uid;
        this.ConnectionEntry = ConnectionEntry;
    }

    /** Callsign of the feed owner — ATAK shows the video under this name on the marker. */
    @JacksonXmlProperty(isAttribute = true)
    public String getSender() {
        return sender;
    }

    public void setSender(String sender) {
        this.sender = sender;
    }

    /** Display label shown in ATAK's video pane. */
    @JacksonXmlProperty(isAttribute = true)
    public String getAlias() {
        return alias;
    }

    public void setAlias(String alias) {
        this.alias = alias;
    }

    @JacksonXmlProperty(isAttribute = true)
    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    @JacksonXmlProperty(isAttribute = true)
    public String getUid() {
        return uid;
    }

    public void setUid(String uid) {
        this.uid = uid;
    }

    @JacksonXmlProperty(localName = "ConnectionEntry")
    public io.opentakserver.opentakicu.cot.ConnectionEntry getConnectionEntry() {
        return ConnectionEntry;
    }

    public void setConnectionEntry(io.opentakserver.opentakicu.cot.ConnectionEntry connectionEntry) {
        ConnectionEntry = connectionEntry;
    }
}
