package org.thoughtcrime.securesms.stories.settings.story

import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.schedulers.Schedulers
import org.thoughtcrime.securesms.database.GroupDatabase
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.recipients.RecipientId
import org.thoughtcrime.securesms.sms.MessageSender
import org.thoughtcrime.securesms.storage.StorageSyncHelper
import org.thoughtcrime.securesms.stories.Stories

class StoriesPrivacySettingsRepository {
  fun markGroupsAsStories(groups: List<RecipientId>): Completable {
    return Completable.fromCallable {
      SignalDatabase.groups.setShowAsStoryState(groups, GroupDatabase.ShowAsStoryState.ALWAYS)
      SignalDatabase.recipients.markNeedsSync(groups)
      StorageSyncHelper.scheduleSyncForDataChange()
    }
  }

  fun setStoriesEnabled(isEnabled: Boolean): Completable {
    return Completable.fromAction {
      SignalStore.storyValues().isFeatureDisabled = !isEnabled
      Stories.onStorySettingsChanged(Recipient.self().id)
      ApplicationDependencies.resetAllNetworkConnections()

      if (!isEnabled) {
        SignalDatabase.mms.getAllOutgoingStories(false, -1).use { reader ->
          reader.map { record -> record.id }
        }.forEach { messageId ->
          MessageSender.sendRemoteDelete(messageId, true)
        }

        SignalDatabase.mms.allIncomingStoriesExceptOnboarding.use { reader ->
          reader.map { record -> record.id }
        }.forEach { messageId ->
          SignalDatabase.mms.deleteMessage(messageId)
        }
      }
    }.subscribeOn(Schedulers.io())
  }

  fun userHasOutgoingStories(): Single<Boolean> {
    return Single.fromCallable {
      SignalDatabase.mms.getAllOutgoingStories(false, -1).use {
        it.iterator().hasNext()
      }
    }.subscribeOn(Schedulers.io())
  }
}
