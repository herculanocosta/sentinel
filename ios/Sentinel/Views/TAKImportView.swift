//
//  TAKImportView.swift
//  Imports a TAK data package (.zip) the operator received from their TAK server admin. After
//  import, shows what was applied and offers a Test Connection button (TCP + TLS handshake).
//

import SwiftUI
import UniformTypeIdentifiers

struct TAKImportView: View {
    /// If non-nil (e.g. opened from AirDrop / Files), import this URL immediately on appear.
    var autoImport: URL? = nil

    @State private var picking = false
    @State private var lastResult: TakImportResult?
    @State private var testRunning = false
    @State private var testResult: TakConnectionTest?
    @State private var importError: String?

    var body: some View {
        Form {
            Section {
                Text("Import a TAK data package (.zip) — the file you received from your server admin. SENTINEL will extract the certificates, apply the server connection settings, and write them to your ATAK settings.")
                    .font(.callout).foregroundStyle(.secondary)
            }
            Section {
                Button {
                    picking = true
                } label: {
                    Label("Choose .zip…", systemImage: "doc.zipper")
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
            }
            if let r = lastResult {
                Section("Imported") {
                    if let h = r.host { row("Server",   h) }
                    if r.port > 0     { row("Port",     "\(r.port)") }
                    row("Protocol", r.ssl ? "SSL/TLS" : "Plain TCP")
                    if let t = r.trustStorePath { row("Trust store", t.lastPathComponent) }
                    if let c = r.clientCertPath { row("Client cert", c.lastPathComponent) }
                    if let d = r.description { row("Description", d) }
                }
                Section {
                    Button {
                        Task { await runTest(with: r) }
                    } label: {
                        if testRunning {
                            HStack { ProgressView(); Text("Testing…") }
                        } else {
                            Label("Test connection", systemImage: "network")
                        }
                    }
                    .disabled(testRunning || r.host == nil)
                }
                if let t = testResult {
                    Section("Connection test") {
                        row(t.tcpOk ? "TCP" : "TCP",
                            t.tcpOk ? "✓ reachable" : "✗ failed",
                            colour: t.tcpOk ? .green : .red)
                        if r.ssl {
                            row("TLS",
                                t.tlsOk ? "✓ handshake OK" : "✗ failed",
                                colour: t.tlsOk ? .green : .red)
                        }
                        if !t.details.isEmpty {
                            Text(t.details).font(.caption).foregroundStyle(.secondary)
                        }
                    }
                }
            }
            if let err = importError {
                Section("Error") {
                    Text(err).foregroundStyle(.red)
                }
            }
        }
        .navigationTitle("TAK data package")
        .navigationBarTitleDisplayMode(.inline)
        .fileImporter(isPresented: $picking,
                      allowedContentTypes: [.zip, UTType("com.pkware.zip-archive") ?? .zip,
                                            UTType(filenameExtension: "zip") ?? .zip],
                      allowsMultipleSelection: false) { result in
            Task { await handle(result) }
        }
        .task {
            if let url = autoImport {
                let scoped = url.startAccessingSecurityScopedResource()
                defer { if scoped { url.stopAccessingSecurityScopedResource() } }
                let r = await TakImporter.importPackage(at: url)
                if r.success { lastResult = r } else { importError = r.message }
            }
        }
    }

    private func row(_ label: String, _ value: String, colour: Color = .primary) -> some View {
        HStack {
            Text(label).foregroundStyle(.secondary)
            Spacer()
            Text(value).foregroundStyle(colour).multilineTextAlignment(.trailing)
        }
    }

    private func handle(_ result: Result<[URL], Error>) async {
        importError = nil; testResult = nil
        switch result {
        case .success(let urls):
            guard let url = urls.first else { return }
            // The picker hands us a security-scoped URL; we need start/stopAccessing to read it.
            let scoped = url.startAccessingSecurityScopedResource()
            defer { if scoped { url.stopAccessingSecurityScopedResource() } }
            let r = await TakImporter.importPackage(at: url)
            if r.success { lastResult = r }
            else { importError = r.message }
        case .failure(let err):
            importError = err.localizedDescription
        }
    }

    private func runTest(with r: TakImportResult) async {
        testRunning = true
        defer { testRunning = false }
        guard let host = r.host, r.port > 0 else { return }
        testResult = await TakConnectionTester.test(
            host: host, port: UInt16(r.port), ssl: r.ssl,
            clientCertPath: r.clientCertPath, clientCertPassword: r.clientCertPassword,
            trustStorePath: r.trustStorePath, trustStorePassword: r.trustStorePassword
        )
    }
}
