//
//  TakDataPackageImporter.swift
//  Mirrors the Android TakDataPackageImporter — reads an ATAK / TAK Server data-package .zip,
//  extracts the `.pref` XML + any `.p12` cert files, parses the `cot_streams` preference block,
//  and applies the matching keys to UserDefaults (same key names as the Android side so the
//  same .zip works on both apps).
//
//  Zip layout (per ATAK spec):
//    *.pref     — XML preference file with a <preference name="cot_streams"> block
//    *.p12      — client cert (referenced by certificateLocation0) + trust store (caLocation0)
//
//  After import:
//    • atak_address      = host  (from connectString0 = "host:port:protocol")
//    • atak_port         = port
//    • atak_ssl          = (protocol ∈ {ssl, tls, https})
//    • atak_send_cot     = true (if enabled0 == "true")
//    • Files written under Documents/TAK/<basename>; absolute paths stored in
//      trust_store_certificate / client_certificate prefs.
//

import Foundation
import ZIPFoundation

struct TakImportResult {
    var success: Bool = false
    var message: String = ""
    var host: String?
    var port: Int = 0
    var ssl: Bool = false
    var trustStorePath: URL?
    var trustStorePassword: String?
    var clientCertPath: URL?
    var clientCertPassword: String?
    var description: String?
}

struct TakConnectionTest {
    var tcpOk: Bool = false
    var tlsOk: Bool = false
    var details: String = ""
}

enum TakImporter {

    /// Imports a TAK data package from a local file URL. The file may be a `file://`-scheme URL
    /// from the SwiftUI `.fileImporter`. Returns a result describing what was applied.
    @MainActor
    static func importPackage(at zipURL: URL) async -> TakImportResult {
        var result = TakImportResult()

        // 1. Open the zip.
        let archive: Archive
        do {
            archive = try Archive(url: zipURL, accessMode: .read)
        } catch {
            result.message = "Could not open zip: \(error.localizedDescription)"
            return result
        }

        // 2. Walk entries: pull the .pref XML into memory; extract .p12/.pem/.jks to sandbox.
        let takDir: URL
        do {
            takDir = try takDirectory()
        } catch {
            result.message = "Could not access sandbox: \(error.localizedDescription)"
            return result
        }

        var prefXML: String?
        var extracted: [String: URL] = [:]   // basename → on-disk URL

        for entry in archive {
            guard entry.type == .file else { continue }
            let normalised = entry.path.replacingOccurrences(of: "\\", with: "/")
            let basename = (normalised as NSString).lastPathComponent
            let lower = basename.lowercased()

            if lower.hasSuffix(".pref") {
                var buf = Data()
                _ = try? archive.extract(entry) { data in buf.append(data) }
                prefXML = String(data: buf, encoding: .utf8)
            } else if lower.hasSuffix(".p12") || lower.hasSuffix(".pem") || lower.hasSuffix(".jks") {
                let dest = takDir.appendingPathComponent(basename)
                try? FileManager.default.removeItem(at: dest)
                _ = try? archive.extract(entry, to: dest)
                extracted[basename] = dest
            }
        }

        guard let xml = prefXML else {
            result.message = "No .pref file found inside the zip."
            return result
        }

        // 3. Parse cot_streams.
        let values = parseCotStreams(xml: xml)
        guard let connect = values["connectString0"] else {
            result.message = "The .pref file is missing connectString0."
            return result
        }

        // 4. Apply to UserDefaults.
        let defaults = UserDefaults.standard
        var summary = ""

        if let desc = values["description0"] { result.description = desc; summary += "Imported: \(desc)\n\n" }

        let parts = connect.split(separator: ":").map(String.init)
        if parts.indices.contains(0) {
            result.host = parts[0]
            defaults.set(parts[0], forKey: Pref.atakServerAddress)
            summary += "Server: \(parts[0])\n"
        }
        if parts.indices.contains(1) {
            result.port = Int(parts[1].trimmingCharacters(in: .whitespaces)) ?? 0
            defaults.set(parts[1], forKey: Pref.atakServerPort)
            summary += "Port: \(parts[1])\n"
        }
        if parts.indices.contains(2) {
            let proto = parts[2].lowercased()
            let isSecure = (proto == "ssl" || proto == "https" || proto == "tls")
            result.ssl = isSecure
            defaults.set(isSecure, forKey: Pref.atakSSL)
            summary += "Protocol: \(parts[2])\n"
        }

        if values["enabled0"]?.lowercased() == "true" {
            defaults.set(true, forKey: Pref.atakSendCoT)
            summary += "CoT streaming: enabled\n"
        }

        if let caLoc = values["caLocation0"] {
            let basename = ((caLoc as NSString).replacingOccurrences(of: "\\", with: "/") as NSString).lastPathComponent
            if let url = extracted[basename] {
                result.trustStorePath = url
                defaults.set(url.path, forKey: Pref.atakTrustStorePath)
                summary += "Trust store: \(basename)\n"
            }
        }
        if let caPwd = values["caPassword0"] {
            result.trustStorePassword = caPwd
            defaults.set(caPwd, forKey: Pref.atakTrustStorePassword)
        }
        if let certLoc = values["certificateLocation0"] {
            let basename = ((certLoc as NSString).replacingOccurrences(of: "\\", with: "/") as NSString).lastPathComponent
            if let url = extracted[basename] {
                result.clientCertPath = url
                defaults.set(url.path, forKey: Pref.atakClientCertPath)
                summary += "Client cert: \(basename)\n"
            }
        }
        if let clientPwd = values["clientPassword0"] {
            result.clientCertPassword = clientPwd
            defaults.set(clientPwd, forKey: Pref.atakClientCertPassword)
        }

        result.success = true
        result.message = summary.isEmpty ? "Imported." : summary
        return result
    }

    // MARK: - XML parsing (cot_streams entries)

    /// XMLParser-based equivalent of the Android pull-parser walk: find
    /// `<preference name="cot_streams">…</preference>`, harvest every `<entry key="…">value</entry>`.
    private static func parseCotStreams(xml: String) -> [String: String] {
        let parser = XMLParser(data: Data(xml.utf8))
        let delegate = CotStreamsParser()
        parser.delegate = delegate
        parser.parse()
        return delegate.values
    }

    // MARK: - Helpers

    private static func takDirectory() throws -> URL {
        let docs = try FileManager.default.url(for: .documentDirectory, in: .userDomainMask,
                                               appropriateFor: nil, create: true)
        let dir = docs.appendingPathComponent("TAK", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }
}

// XMLParserDelegate isolates per-import state.
private final class CotStreamsParser: NSObject, XMLParserDelegate {
    var values: [String: String] = [:]
    private var inCotStreams = false
    private var currentKey: String?
    private var buffer = ""

    func parser(_ parser: XMLParser, didStartElement elementName: String, namespaceURI: String?,
                qualifiedName qName: String?, attributes attributeDict: [String : String] = [:]) {
        switch elementName {
        case "preference":
            inCotStreams = (attributeDict["name"] == "cot_streams")
        case "entry":
            if inCotStreams {
                currentKey = attributeDict["key"]
                buffer = ""
            }
        default: break
        }
    }
    func parser(_ parser: XMLParser, foundCharacters string: String) {
        if currentKey != nil { buffer += string }
    }
    func parser(_ parser: XMLParser, didEndElement elementName: String, namespaceURI: String?,
                qualifiedName qName: String?) {
        if elementName == "entry", let key = currentKey {
            values[key] = buffer.trimmingCharacters(in: .whitespacesAndNewlines)
            currentKey = nil
            buffer = ""
        } else if elementName == "preference" {
            inCotStreams = false
        }
    }
}
