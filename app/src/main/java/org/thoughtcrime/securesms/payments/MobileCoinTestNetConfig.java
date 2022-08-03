package org.thoughtcrime.securesms.payments;

import android.net.Uri;

import androidx.annotation.NonNull;

import com.mobilecoin.lib.ClientConfig;
import com.mobilecoin.lib.Verifier;
import com.mobilecoin.lib.exceptions.AttestationException;
import com.mobilecoin.lib.util.Hex;

import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.util.Base64;
import org.whispersystems.signalservice.api.SignalServiceAccountManager;
import org.whispersystems.signalservice.internal.push.AuthCredentials;

import java.io.IOException;
import java.security.cert.X509Certificate;
import java.util.Set;

final class MobileCoinTestNetConfig extends MobileCoinConfig {

  private static final short SECURITY_VERSION      = 1;
  private static final short CONSENSUS_PRODUCT_ID  = 1;
  private static final short FOG_LEDGER_PRODUCT_ID = 2;
  private static final short FOG_VIEW_PRODUCT_ID   = 3;
  private static final short FOG_REPORT_PRODUCT_ID = 4;

  private final SignalServiceAccountManager signalServiceAccountManager;

  public MobileCoinTestNetConfig(@NonNull SignalServiceAccountManager signalServiceAccountManager) {
    this.signalServiceAccountManager = signalServiceAccountManager;
  }

  @Override
  @NonNull Uri getConsensusUri() {
    return Uri.parse("mc://node1.test.mobilecoin.com");
  }

  @Override
  @NonNull Uri getFogUri() {
    return Uri.parse("fog://service.fog.mob.staging.namda.net");
  }

  @Override
  @NonNull Uri getFogReportUri() {
    return Uri.parse("fog://fog-rpt-stg.namda.net");
  }

  @Override
  @NonNull byte[] getFogAuthoritySpki() {
    return Base64.decodeOrThrow("MIICIjANBgkqhkiG9w0BAQEFAAOCAg8AMIICCgKCAgEAoCMq8nnjTq5EEQ4EI7yrABL9P4y4h1P/h0DepWgXx+w/fywcfRSZINxbaMpvcV3uSJayExrpV1KmaS2wfASeYhSj+rEzAm0XUOw3Q94NOx5A/dOQag/d1SS6/QpF3PQYZTULnRFetmM4yzEnXsXcWtzEu0hh02wYJbLeAq4CCcPTPe2qckrbUP9sD18/KOzzNeypF4p5dQ2m/ezfxtgaLvdUMVDVIAs2v9a5iu6ce4bIcwTIUXgX0w3+UKRx8zqowc3HIqo9yeaGn4ZOwQHvAJZecPmb2pH1nK+BtDUvHpvf+Y3/NJxwh+IPp6Ef8aoUxs2g5oIBZ3Q31fjS2Bh2gmwoVooyytEysPAHvRPVBxXxLi36WpKfk1Vq8K7cgYh3IraOkH2/l2Pyi8EYYFkWsLYofYogaiPzVoq2ZdcizfoJWIYei5mgq+8m0ZKZYLebK1i2GdseBJNIbSt3wCNXZxyN6uqFHOCB29gmA5cbKvs/j9mDz64PJe9LCanqcDQV1U5l9dt9UdmUt7Ab1PjBtoIFaP+u473Z0hmZdCgAivuiBMMYMqt2V2EIw4IXLASE3roLOYp0p7h0IQHb+lVIuEl0ZmwAI30ZmzgcWc7RBeWD1/zNt55zzhfPRLx/DfDY5Kdp6oFHWMvI2r1/oZkdhjFp7pV6qrl7vOyR5QqmuRkCAwEAAQ==");
  }

  @Override
  @NonNull AuthCredentials getAuth() throws IOException {
    return signalServiceAccountManager.getPaymentsAuthorization();
  }

  @Override
  @NonNull ClientConfig getConfig() {
    try {
      byte[]               mrEnclaveConsensus  = Hex.toByteArray("4f134dcfd9c0885956f2f9af0f05c2050d8bdee2dc63b468a640670d7adeb7f8");
      byte[]               mrEnclaveReport     = Hex.toByteArray("8f2f3bf81f24bf493fa6d76e29e0f081815022592b1e854f95bda750aece7452");
      byte[]               mrEnclaveLedger     = Hex.toByteArray("685481b33f2846585f33506ab65649c98a4a6d1244989651fd0fcde904ebd82f");
      byte[]               mrEnclaveView       = Hex.toByteArray("719ca43abbe02f507bb91ea11ff8bc900aa86363a7d7e77b8130426fc53d8684");
      byte[]               mrSigner            = Hex.toByteArray("bf7fa957a6a94acb588851bc8767e0ca57706c79f4fc2aa6bcb993012c3c386c");
      Set<X509Certificate> trustRoots          = getTrustRoots(R.raw.signal_mobilecoin_authority);
      ClientConfig         config              = new ClientConfig();
      String[]             hardeningAdvisories = {"INTEL-SA-00334"};

      config.logAdapter = new MobileCoinLogAdapter();
      config.fogView    = new ClientConfig.Service().withTrustRoots(trustRoots)
                                                    .withVerifier(new Verifier().withMrEnclave(mrEnclaveView, null, hardeningAdvisories)
                                                                                .withMrSigner(mrSigner, FOG_VIEW_PRODUCT_ID, SECURITY_VERSION, null, hardeningAdvisories));
      config.fogLedger  = new ClientConfig.Service().withTrustRoots(trustRoots)
                                                    .withVerifier(new Verifier().withMrEnclave(mrEnclaveLedger, null, hardeningAdvisories)
                                                                                .withMrSigner(mrSigner, FOG_LEDGER_PRODUCT_ID, SECURITY_VERSION, null, hardeningAdvisories));
      config.consensus  = new ClientConfig.Service().withTrustRoots(trustRoots)
                                                    .withVerifier(new Verifier().withMrEnclave(mrEnclaveConsensus, null, hardeningAdvisories)
                                                                                .withMrSigner(mrSigner, CONSENSUS_PRODUCT_ID, SECURITY_VERSION, null, hardeningAdvisories));
      config.report     = new ClientConfig.Service().withVerifier(new Verifier().withMrEnclave(mrEnclaveReport, null, hardeningAdvisories)
                                                                                .withMrSigner(mrSigner, FOG_REPORT_PRODUCT_ID, SECURITY_VERSION, null, hardeningAdvisories));
      return config;
    } catch (AttestationException ex) {
      throw new IllegalStateException();
    }
  }
}
