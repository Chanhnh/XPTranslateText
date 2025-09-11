package tianci.dev.xptranslatetext.util;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Provides a built-in pinned certificate for the local HTTPS client.
 * Used when the module's assets are not reachable from the hooked process
 * (e.g., work profile or cloned app where the module package is not installed).
 *
 * Source of truth: the PEM content mirrors the asset file
 * {@code assets/local_https_server.crt}. If the server certificate is rotated,
 * update that asset and this embedded string together to keep pinning valid.
 */
public final class LocalCerts {

    private LocalCerts() { }

    /**
     * Returns an InputStream for the pinned server certificate (PEM).
     * The certificate is intended to validate TLS connections to 127.0.0.1 only.
     */
    public static InputStream openLocalHttpsServerCrt() {
        String pem = "-----BEGIN CERTIFICATE-----\n"
                + "MIIDODCCAiCgAwIBAgIUXWQNM3sjf5q19Qcf9ECqfWY7mNQwDQYJKoZIhvcNAQEL\n"
                + "BQAwFDESMBAGA1UEAwwJbG9jYWxob3N0MB4XDTI1MDkxMTAxNDA0MloXDTM1MDkw\n"
                + "OTAxNDA0MlowFDESMBAGA1UEAwwJbG9jYWxob3N0MIIBIjANBgkqhkiG9w0BAQEF\n"
                + "AAOCAQ8AMIIBCgKCAQEA1N2vffo2nrByAADb3isJStP/h9wu/hYc1KxNahKNLLG6\n"
                + "LGimMpJLD0OPrCdDA09WEgYd7sStB4YNE5rQZ4hzR6/165+yUgGnmM7PNpl+ra+N\n"
                + "WC1x/F+hnp0kZqWR41pGUb1AT2ms8OvAtXoy6+ynktPmvvYAVuIOoBVS6Ht1ZUxk\n"
                + "avg/Xtdu+3v0oEOePCBMKYdoSFA3yzyabnIFmRp4D7oonij8404DxzoOlfD3y7g/\n"
                + "4bsjrt396sf2mA93zAVQbidYbpuMT8E+4nNK2YinXDQnghE+eTW4gjCZell0vOOT\n"
                + "9pz+68o5i6FXuCBhhOpQjyQIvICjvlwvPA8C6euhJQIDAQABo4GBMH8wHQYDVR0O\n"
                + "BBYEFE+5I9fkM8GSIZyeZZwcMSlTA6uiMB8GA1UdIwQYMBaAFE+5I9fkM8GSIZye\n"
                + "ZZwcMSlTA6uiMA8GA1UdEwEB/wQFMAMBAf8wLAYDVR0RBCUwI4IJbG9jYWxob3N0\n"
                + "hwR/AAABhxAAAAAAAAAAAAAAAAAAAAABMA0GCSqGSIb3DQEBCwUAA4IBAQCPNc5b\n"
                + "I1xGdyDEmr0JdZR0k6XcGrr2Mz1311Bawrabpi8ZNFPAZnbiI/uaiAYppOaSPEGF\n"
                + "NYR5Mye3iRiDTGitItcr2Er4HUIcefg8jlP6XuLU2YHjpcqR4Ertz8T+AsFhNTyn\n"
                + "YjFZhTe+HBTgc2RKSiDcXa6pDeXiEUAstWXHs7HKZeB3CsmTthtAqorRs0mwt+XM\n"
                + "dWxR0dAl7keJduoDkH14Zwxam2NTQqlKHdWurZOiLuG3Wec/WlU8UwgA0mda05DJ\n"
                + "fOYLyCTCzLW7kmWj6uwIIZ7QdL5ebInTICT8a2SD2gNR/bmBYruAA9UAZesORZrR\n"
                + "2+bZO9YKBxZ6r6n8\n"
                + "-----END CERTIFICATE-----\n";
        return new ByteArrayInputStream(pem.getBytes(StandardCharsets.UTF_8));
    }
}
