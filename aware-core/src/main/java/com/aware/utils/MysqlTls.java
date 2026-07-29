package com.aware.utils;

import android.content.Context;

import com.aware.R;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;

/**
 * Supplies the TLS parameters the MySQL driver uses when it opens a connection to the research
 * database.
 *
 * TLS carries two guarantees: the traffic is unreadable in transit, and the host that answers is the
 * one the study means to reach. The second one holds once the driver checks the certificate it is
 * offered against an authority it already trusts. The research database presents a certificate from
 * the study's own authority, so that authority's certificate travels inside the APK as
 * {@code res/raw/mysql_ca.pem} and the driver is pointed at a trust store built from it. A
 * certificate from any other authority ends the handshake, which is what confines the connection to
 * the study's own server rather than to whichever host occupies the network path.
 *
 * The driver reads its trust store from a URL, so the bundled certificate is turned into a PKCS#12
 * file in the app's private storage the first time a connection is opened and reused from there. The
 * file name carries a digest of the certificate, so an APK shipping a different authority builds its
 * own store.
 *
 * Rotating the database server's certificates therefore goes together with shipping the new
 * authority's {@code mysql_ca.pem} in a new APK.
 */
public final class MysqlTls {

    /** Alias the authority's certificate is stored under. */
    private static final String CA_ALIAS = "study-database-ca";

    /**
     * Integrity password for the trust store file. A trust store holds certificates that are public
     * by nature — this one ships inside the APK — and PKCS#12 asks for a password to open a store at
     * all.
     */
    static final String TRUST_STORE_PASSWORD = "awaretruststore";

    /** Characters of the certificate digest that name the store file. */
    private static final int DIGEST_CHARS_IN_NAME = 16;

    /** The parameters for this process, kept once the store behind them is known to exist. */
    private static volatile String cachedParameters;

    private MysqlTls() {
    }

    /**
     * Name of the trust store holding a certificate with the given digest. Pure and side-effect free
     * so it can be unit-tested without a device.
     *
     * @param digestHex hex digest of the certificate's encoded form
     * @return a file name that belongs to that certificate alone
     */
    static String trustStoreName(String digestHex) {
        return "mysql_truststore_" + digestHex.substring(0, DIGEST_CHARS_IN_NAME) + ".p12";
    }

    /**
     * The JDBC URL query parameters that have the driver verify the server's certificate against a
     * trust store. Pure and side-effect free so it can be unit-tested without a device.
     *
     * @param trustStorePath absolute path of the trust store file
     * @return a fragment opening with {@code &}, to append to a JDBC URL that already carries a query
     */
    static String sslParameters(String trustStorePath) {
        return "&useSSL=true&requireSSL=true&verifyServerCertificate=true"
                + "&trustCertificateKeyStoreType=PKCS12"
                + "&trustCertificateKeyStoreUrl=file:" + trustStorePath
                + "&trustCertificateKeyStorePassword=" + TRUST_STORE_PASSWORD;
    }

    /**
     * Parameters for a certificate-verified connection, building the trust store the first time a
     * process needs it.
     *
     * The caller decides what a failure here means: every connection path treats it the same way as a
     * database it could not reach, so a batch is kept for the next sync and the participant is left
     * alone.
     *
     * @param context application context
     * @return the fragment described by {@link #sslParameters}
     * @throws IOException              the bundled certificate or the trust store file is unreadable
     * @throws GeneralSecurityException the certificate cannot be read, digested or stored
     */
    public static String connectionParameters(Context context)
            throws IOException, GeneralSecurityException {
        String parameters = cachedParameters;
        if (parameters != null) return parameters;

        Certificate authority = bundledAuthority(context);
        File store = new File(context.getFilesDir(), trustStoreName(digestOf(authority)));
        if (!store.exists()) writeTrustStore(authority, store);

        parameters = sslParameters(store.getAbsolutePath());
        cachedParameters = parameters;
        return parameters;
    }

    /** Reads the certificate authority that ships with the app. */
    private static Certificate bundledAuthority(Context context)
            throws IOException, GeneralSecurityException {
        InputStream certificate = context.getResources().openRawResource(R.raw.mysql_ca);
        try {
            return CertificateFactory.getInstance("X.509").generateCertificate(certificate);
        } finally {
            certificate.close();
        }
    }

    /** Hex SHA-256 of a certificate's encoded form, which names the store built from it. */
    private static String digestOf(Certificate certificate) throws GeneralSecurityException {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded());
        StringBuilder hex = new StringBuilder(digest.length * 2);
        for (byte value : digest) {
            hex.append(Character.forDigit((value >> 4) & 0xF, 16));
            hex.append(Character.forDigit(value & 0xF, 16));
        }
        return hex.toString();
    }

    /** Writes a trust store whose sole entry is the study's certificate authority. */
    private static void writeTrustStore(Certificate authority, File store)
            throws IOException, GeneralSecurityException {
        KeyStore trust = KeyStore.getInstance("PKCS12");
        trust.load(null, null);
        trust.setCertificateEntry(CA_ALIAS, authority);

        OutputStream out = new FileOutputStream(store);
        try {
            trust.store(out, TRUST_STORE_PASSWORD.toCharArray());
        } finally {
            out.close();
        }
    }
}
