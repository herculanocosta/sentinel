//
//  TakConnectionTest.swift
//  Verify a TAK server is reachable using the freshly-imported credentials. Mirrors what the
//  Android importer does after applying preferences — TCP connect then, if SSL, a TLS handshake
//  with the client cert + trust store loaded from the imported .p12 files.
//

import Foundation
import Network
import Security

enum TakConnectionTester {

    @MainActor
    static func test(host: String, port: UInt16, ssl: Bool,
                     clientCertPath: URL? = nil, clientCertPassword: String? = nil,
                     trustStorePath: URL? = nil, trustStorePassword: String? = nil,
                     timeoutSec: TimeInterval = 5) async -> TakConnectionTest {

        var result = TakConnectionTest()
        let params: NWParameters
        if ssl {
            params = NWParameters(tls: tlsOptions(clientCertPath: clientCertPath,
                                                  clientCertPassword: clientCertPassword,
                                                  trustStorePath: trustStorePath,
                                                  trustStorePassword: trustStorePassword,
                                                  expectedHost: host),
                                  tcp: .init())
        } else {
            params = .tcp
        }

        let endpoint = NWEndpoint.hostPort(host: .init(host),
                                           port: NWEndpoint.Port(integerLiteral: port))
        let conn = NWConnection(to: endpoint, using: params)

        await withCheckedContinuation { (cont: CheckedContinuation<Void, Never>) in
            let timeout = DispatchWorkItem { conn.cancel() }
            DispatchQueue.global().asyncAfter(deadline: .now() + timeoutSec, execute: timeout)

            conn.stateUpdateHandler = { state in
                switch state {
                case .ready:
                    timeout.cancel()
                    result.tcpOk = true
                    if ssl { result.tlsOk = true; result.details = "TLS handshake OK" }
                    else   { result.details = "TCP reachable" }
                    conn.cancel(); cont.resume()
                case .failed(let err):
                    timeout.cancel()
                    result.details = ssl ? "TLS handshake failed: \(err.localizedDescription)"
                                         : "TCP connect failed: \(err.localizedDescription)"
                    cont.resume()
                case .cancelled:
                    if result.details.isEmpty { result.details = "Timed out after \(Int(timeoutSec))s" }
                    cont.resume()
                default: break
                }
            }
            conn.start(queue: .global(qos: .utility))
        }
        return result
    }

    // MARK: - TLS options with imported client identity

    private static func tlsOptions(clientCertPath: URL?, clientCertPassword: String?,
                                    trustStorePath: URL?, trustStorePassword: String?,
                                    expectedHost: String) -> NWProtocolTLS.Options {
        let options = NWProtocolTLS.Options()
        let sec = options.securityProtocolOptions

        // Client identity (mutual TLS) from the imported .p12.
        if let path = clientCertPath,
           let identity = loadPKCS12Identity(at: path, password: clientCertPassword ?? "") {
            sec_protocol_options_set_local_identity(sec, identity)
        }

        // Trust verification: when a trust store is provided, accept anything chaining to it.
        // We DO still verify the certificate matches expectedHost (no MITM). When no trust store
        // is provided, fall back to the system trust roots.
        if let trustPath = trustStorePath,
           let trustRefs = loadPKCS12TrustCertificates(at: trustPath, password: trustStorePassword ?? "") {
            sec_protocol_options_set_verify_block(sec, { _, sec_trust, completion in
                let trust = sec_trust_copy_ref(sec_trust).takeRetainedValue()
                SecTrustSetAnchorCertificates(trust, trustRefs as CFArray)
                SecTrustSetAnchorCertificatesOnly(trust, true)
                SecTrustEvaluateAsyncWithError(trust, .global()) { _, ok, _ in
                    completion(ok)
                }
            }, .global())
        }

        return options
    }

    /// Load a `.p12` file and return the first identity (cert + private key) inside it,
    /// wrapped as a `sec_identity_t` that Network framework's TLS layer expects.
    private static func loadPKCS12Identity(at url: URL, password: String) -> sec_identity_t? {
        guard let data = try? Data(contentsOf: url) else { return nil }
        let options: [String: Any] = [kSecImportExportPassphrase as String: password]
        var items: CFArray?
        let status = SecPKCS12Import(data as CFData, options as CFDictionary, &items)
        guard status == errSecSuccess,
              let array = items as? [[String: Any]],
              let identityAny = array.first?[kSecImportItemIdentity as String] else { return nil }
        let identity = identityAny as! SecIdentity
        return sec_identity_create(identity)
    }

    /// Load all certificates out of a `.p12` so they can be used as TLS trust anchors.
    private static func loadPKCS12TrustCertificates(at url: URL, password: String) -> [SecCertificate]? {
        guard let data = try? Data(contentsOf: url) else { return nil }
        let options: [String: Any] = [kSecImportExportPassphrase as String: password]
        var items: CFArray?
        let status = SecPKCS12Import(data as CFData, options as CFDictionary, &items)
        guard status == errSecSuccess, let array = items as? [[String: Any]] else { return nil }
        var out: [SecCertificate] = []
        for item in array {
            if let identityAny = item[kSecImportItemIdentity as String] {
                let identity = identityAny as! SecIdentity
                var cert: SecCertificate?
                if SecIdentityCopyCertificate(identity, &cert) == errSecSuccess, let cert = cert {
                    out.append(cert)
                }
            }
            if let chain = item[kSecImportItemCertChain as String] as? [SecCertificate] {
                out.append(contentsOf: chain)
            }
        }
        return out.isEmpty ? nil : out
    }
}
