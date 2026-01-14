package com.ureca.billing.core.security.crypto;

import javax.crypto.SecretKey;

public interface CryptoKeyProvider {
    SecretKey getCurrentKey();
}
