//
//  ServerPresetTests.swift
//  Sanity checks for the URL builder + JSON round-trip — the bits we can't easily eyeball.
//

import XCTest
@testable import Sentinel

final class ServerPresetTests: XCTestCase {

    func testSuggestObserverURL_RTSP_appendsQuestionTCP() {
        let url = ServerPreset.suggestObserverURL(
            protocolName: "rtsp", address: "tak.example.com", port: "8554", path: "feed")
        XCTAssertEqual(url, "rtsp://tak.example.com:8554/feed?tcp")
    }

    func testSuggestObserverURL_SRT_usesReadStreamID() {
        let url = ServerPreset.suggestObserverURL(
            protocolName: "srt", address: "tak.example.com", port: "8890", path: "feed")
        XCTAssertEqual(url, "srt://tak.example.com:8890?streamid=read:feed")
    }

    func testSuggestObserverURL_RTMP_usesDefaultPortWhenBlank() {
        let url = ServerPreset.suggestObserverURL(
            protocolName: "rtmp", address: "host", port: "", path: "live")
        XCTAssertEqual(url, "rtmp://host:1935/live")
    }

    func testMapPublishToViewer_SRT() {
        XCTAssertEqual(StreamProtocols.mapToViewer("srt"),   "srt")
        XCTAssertEqual(StreamProtocols.mapToViewer("rtsps"), "rtsp")
        XCTAssertEqual(StreamProtocols.mapToViewer("rtmp"),  "rtmp")
        XCTAssertEqual(StreamProtocols.mapToViewer("udp"),   "rtsp")
    }

    func testJSONRoundTrip() throws {
        let p = ServerPreset(name: "Home", protocolName: "srt", address: "h", port: "8890",
                             path: "feed", username: "", password: "", tcp: false,
                             observerURL: "srt://h:8890?streamid=read:feed")
        let data = try JSONEncoder().encode(p)
        let back = try JSONDecoder().decode(ServerPreset.self, from: data)
        XCTAssertEqual(p, back)
    }

    func testCoTEvent_includesConnectionEntryForRTSP() {
        let evt = CoTEvent(uid: "abc", callsign: "TANGO",
                           viewerURL: "rtsp://host:8554/feed?tcp",
                           alias: "Tango feed",
                           lat: 38.7, lon: -9.1, hae: 0)
        let xml = evt.toXML()
        XCTAssertTrue(xml.contains("type=\"b-i-v\""))
        XCTAssertTrue(xml.contains("<ConnectionEntry"))
        XCTAssertTrue(xml.contains("protocol=\"rtsp\""))
        XCTAssertTrue(xml.contains("rtspReliable=\"1\""))
    }
}
