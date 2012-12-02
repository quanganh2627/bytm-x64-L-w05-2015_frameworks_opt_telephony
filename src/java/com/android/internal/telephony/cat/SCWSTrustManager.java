package com.android.internal.telephony.cat;

import java.security.cert.CertificateException;
import java.security.cert.X509Certificate ;
import javax.net.ssl.X509TrustManager;

class SCWSTrustManager implements X509TrustManager {
    // just trust all now.
    public void checkClientTrusted (X509Certificate[] cert, String authType)
            throws CertificateException {
    }

    public void checkServerTrusted (X509Certificate[] cert, String authType)
            throws CertificateException {
    }

    public X509Certificate[] getAcceptedIssuers() {
        return null;
    }
}

