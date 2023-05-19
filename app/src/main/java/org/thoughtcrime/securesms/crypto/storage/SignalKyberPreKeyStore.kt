/*
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.crypto.storage

import org.signal.libsignal.protocol.InvalidKeyIdException
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.KyberPreKeyStore
import org.thoughtcrime.securesms.crypto.ReentrantSessionLock
import org.thoughtcrime.securesms.database.SignalDatabase
import org.whispersystems.signalservice.api.push.ServiceId
import kotlin.jvm.Throws

/**
 * An implementation of the [KyberPreKeyStore] that stores entries in [org.thoughtcrime.securesms.database.KyberPreKeyTable].
 */
class SignalKyberPreKeyStore(private val selfServiceId: ServiceId) : KyberPreKeyStore {

  @Throws(InvalidKeyIdException::class)
  override fun loadKyberPreKey(kyberPreKeyId: Int): KyberPreKeyRecord {
    ReentrantSessionLock.INSTANCE.acquire().use {
      return SignalDatabase.kyberPreKeys.get(selfServiceId, kyberPreKeyId)?.record ?: throw InvalidKeyIdException("Missing kyber prekey with ID: $kyberPreKeyId")
    }
  }

  override fun loadKyberPreKeys(): List<KyberPreKeyRecord> {
    ReentrantSessionLock.INSTANCE.acquire().use {
      return SignalDatabase.kyberPreKeys.getAll(selfServiceId).map { it.record }
    }
  }

  override fun storeKyberPreKey(kyberPreKeyId: Int, record: KyberPreKeyRecord) {
    error("This method is only used in tests")
  }

  override fun containsKyberPreKey(kyberPreKeyId: Int): Boolean {
    ReentrantSessionLock.INSTANCE.acquire().use {
      return SignalDatabase.kyberPreKeys.contains(selfServiceId, kyberPreKeyId)
    }
  }

  override fun markKyberPreKeyUsed(kyberPreKeyId: Int) {
    ReentrantSessionLock.INSTANCE.acquire().use {
      SignalDatabase.kyberPreKeys.deleteIfNotLastResort(selfServiceId, kyberPreKeyId)
    }
  }
}
