package org.thoughtcrime.securesms.conversation.drafts

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.text.Spannable
import android.text.SpannableString
import androidx.annotation.WorkerThread
import com.bumptech.glide.load.engine.DiskCacheStrategy
import io.reactivex.rxjava3.core.Maybe
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.schedulers.Schedulers
import org.signal.core.util.StreamUtil
import org.signal.core.util.concurrent.SignalExecutors
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.components.location.SignalPlace
import org.thoughtcrime.securesms.components.mention.MentionAnnotation
import org.thoughtcrime.securesms.conversation.ConversationIntents
import org.thoughtcrime.securesms.conversation.ConversationMessage
import org.thoughtcrime.securesms.conversation.ConversationMessage.ConversationMessageFactory
import org.thoughtcrime.securesms.conversation.MessageStyler
import org.thoughtcrime.securesms.database.DraftTable
import org.thoughtcrime.securesms.database.DraftTable.Drafts
import org.thoughtcrime.securesms.database.MentionUtil
import org.thoughtcrime.securesms.database.MessageTypes
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.database.ThreadTable
import org.thoughtcrime.securesms.database.adjustBodyRanges
import org.thoughtcrime.securesms.database.model.MediaMmsMessageRecord
import org.thoughtcrime.securesms.database.model.Mention
import org.thoughtcrime.securesms.database.model.MessageId
import org.thoughtcrime.securesms.database.model.MessageRecord
import org.thoughtcrime.securesms.database.model.databaseprotos.BodyRangeList
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies
import org.thoughtcrime.securesms.mediasend.Media
import org.thoughtcrime.securesms.mms.DecryptableStreamUriLoader.DecryptableUri
import org.thoughtcrime.securesms.mms.GifSlide
import org.thoughtcrime.securesms.mms.GlideApp
import org.thoughtcrime.securesms.mms.GlideRequests
import org.thoughtcrime.securesms.mms.ImageSlide
import org.thoughtcrime.securesms.mms.PartAuthority
import org.thoughtcrime.securesms.mms.QuoteId
import org.thoughtcrime.securesms.mms.Slide
import org.thoughtcrime.securesms.mms.SlideFactory
import org.thoughtcrime.securesms.mms.StickerSlide
import org.thoughtcrime.securesms.providers.BlobProvider
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.util.Base64
import org.thoughtcrime.securesms.util.MediaUtil
import org.thoughtcrime.securesms.util.concurrent.SerialMonoLifoExecutor
import org.thoughtcrime.securesms.util.hasTextSlide
import org.thoughtcrime.securesms.util.requireTextSlide
import java.io.IOException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class DraftRepository(
  private val context: Context = ApplicationDependencies.getApplication(),
  private val threadTable: ThreadTable = SignalDatabase.threads,
  private val draftTable: DraftTable = SignalDatabase.drafts,
  private val saveDraftsExecutor: Executor = SerialMonoLifoExecutor(SignalExecutors.BOUNDED),
  private val conversationArguments: ConversationIntents.Args? = null
) {

  companion object {
    val TAG = Log.tag(DraftRepository::class.java)
  }

  fun getShareOrDraftData(): Maybe<Pair<ShareOrDraftData, Drafts?>> {
    return Maybe.fromCallable<Pair<ShareOrDraftData, Drafts?>> { getShareOrDraftDataInternal() }
      .observeOn(Schedulers.io())
  }

  private fun getShareOrDraftDataInternal(): Pair<ShareOrDraftData, Drafts?>? {
    val shareText = conversationArguments?.draftText
    val shareMedia = conversationArguments?.draftMedia
    val shareContentType = conversationArguments?.draftContentType
    val shareMediaType = conversationArguments?.draftMediaType
    val shareMediaList = conversationArguments?.media ?: emptyList()
    val stickerLocator = conversationArguments?.stickerLocator
    val borderless = conversationArguments?.isBorderless ?: false

    if (stickerLocator != null && shareMedia != null) {
      val slide = StickerSlide(context, shareMedia, 0, stickerLocator, shareContentType!!)
      return ShareOrDraftData.SendSticker(slide) to null
    }

    if (shareMedia != null && shareContentType != null && borderless) {
      val details = getKeyboardImageDetails(GlideApp.with(context), shareMedia)

      if (details == null || !details.hasTransparency) {
        return ShareOrDraftData.SetMedia(shareMedia, shareMediaType!!, null) to null
      }

      val slide: Slide? = if (MediaUtil.isGif(shareContentType)) {
        GifSlide(context, shareMedia, 0, details.width, details.height, true, null)
      } else if (MediaUtil.isImageType(shareContentType)) {
        ImageSlide(context, shareMedia, shareContentType, 0, details.width, details.height, true, null, null)
      } else {
        Log.w(TAG, "Attempting to send unsupported non-image via keyboard share")
        null
      }

      return if (slide != null) ShareOrDraftData.SendKeyboardImage(slide) to null else null
    }

    if (shareMediaList.isNotEmpty()) {
      return ShareOrDraftData.StartSendMedia(shareMediaList, shareText) to null
    }

    if (shareMedia != null && shareMediaType != null) {
      return ShareOrDraftData.SetMedia(shareMedia, shareMediaType, shareText) to null
    }

    if (shareText != null) {
      return ShareOrDraftData.SetText(shareText) to null
    }

    if (conversationArguments?.canInitializeFromDatabase() == true) {
      val (drafts, updatedText) = loadDraftsInternal(conversationArguments.threadId)

      val draftText: CharSequence? = drafts.firstOrNull { it.type == DraftTable.Draft.TEXT }?.let { updatedText ?: it.value }

      val location: SignalPlace? = drafts.firstOrNull { it.type == DraftTable.Draft.LOCATION }?.let { SignalPlace.deserialize(it.value) }
      if (location != null) {
        return ShareOrDraftData.SetLocation(location, draftText) to drafts
      }

      val audio: Uri? = drafts.firstOrNull { it.type == DraftTable.Draft.AUDIO }?.let { Uri.parse(it.value) }
      if (audio != null) {
        return ShareOrDraftData.SetMedia(audio, SlideFactory.MediaType.AUDIO, null) to drafts
      }

      val quote: ConversationMessage? = drafts.firstOrNull { it.type == DraftTable.Draft.QUOTE }?.let { loadDraftQuoteInternal(it.value) }
      if (quote != null) {
        return ShareOrDraftData.SetQuote(quote, draftText) to drafts
      }

      val messageEdit: ConversationMessage? = drafts.firstOrNull { it.type == DraftTable.Draft.MESSAGE_EDIT }?.let { loadDraftMessageEditInternal(it.value) }
      if (messageEdit != null) {
        return ShareOrDraftData.SetEditMessage(messageEdit) to drafts
      }

      if (draftText != null) {
        return ShareOrDraftData.SetText(draftText) to drafts
      }
    }

    // no share or draft
    return null
  }

  fun deleteVoiceNoteDraftData(draft: DraftTable.Draft?) {
    if (draft != null) {
      SignalExecutors.BOUNDED.execute {
        BlobProvider.getInstance().delete(context, Uri.parse(draft.value).buildUpon().clearQuery().build())
      }
    }
  }

  fun saveDrafts(recipient: Recipient?, threadId: Long, distributionType: Int, drafts: Drafts) {
    require(threadId != -1L || recipient != null)

    saveDraftsExecutor.execute {
      if (drafts.isNotEmpty()) {
        val actualThreadId = if (threadId == -1L) {
          threadTable.getOrCreateThreadIdFor(recipient!!, distributionType)
        } else {
          threadId
        }

        draftTable.replaceDrafts(actualThreadId, drafts)
        if (drafts.shouldUpdateSnippet()) {
          threadTable.updateSnippet(actualThreadId, drafts.getSnippet(context), drafts.getUriSnippet(), System.currentTimeMillis(), MessageTypes.BASE_DRAFT_TYPE, true)
        } else {
          threadTable.update(actualThreadId, unarchive = false, allowDeletion = false)
        }
      } else if (threadId > 0) {
        draftTable.clearDrafts(threadId)
        threadTable.update(threadId, unarchive = false, allowDeletion = false)
      }
    }
  }

  @Deprecated("Not needed for CFv2")
  fun loadDrafts(threadId: Long): Single<DatabaseDraft> {
    return Single.fromCallable {
      loadDraftsInternal(threadId)
    }.subscribeOn(Schedulers.io())
  }

  private fun loadDraftsInternal(threadId: Long): DatabaseDraft {
    val drafts: Drafts = draftTable.getDrafts(threadId)
    val bodyRangesDraft: DraftTable.Draft? = drafts.getDraftOfType(DraftTable.Draft.BODY_RANGES)
    val textDraft: DraftTable.Draft? = drafts.getDraftOfType(DraftTable.Draft.TEXT)
    var updatedText: Spannable? = null

    if (textDraft != null && bodyRangesDraft != null) {
      val bodyRanges: BodyRangeList = BodyRangeList.parseFrom(Base64.decodeOrThrow(bodyRangesDraft.value))
      val mentions: List<Mention> = MentionUtil.bodyRangeListToMentions(bodyRanges)

      val updated = MentionUtil.updateBodyAndMentionsWithDisplayNames(context, textDraft.value, mentions)

      updatedText = SpannableString(updated.body)
      MentionAnnotation.setMentionAnnotations(updatedText, updated.mentions)
      MessageStyler.style(id = MessageStyler.DRAFT_ID, messageRanges = bodyRanges.adjustBodyRanges(updated.bodyAdjustments), span = updatedText, hideSpoilerText = false)
    }

    return DatabaseDraft(drafts, updatedText)
  }

  @Deprecated("Not needed for CFv2")
  fun loadDraftQuote(serialized: String): Maybe<ConversationMessage> {
    return Maybe.fromCallable { loadDraftQuoteInternal(serialized) }
  }

  private fun loadDraftQuoteInternal(serialized: String): ConversationMessage? {
    val quoteId: QuoteId = QuoteId.deserialize(context, serialized) ?: return null
    val messageRecord: MessageRecord = SignalDatabase.messages.getMessageFor(quoteId.id, quoteId.author)?.let {
      if (it is MediaMmsMessageRecord) {
        it.withAttachments(context, SignalDatabase.attachments.getAttachmentsForMessage(it.id))
      } else {
        it
      }
    } ?: return null

    val threadRecipient = requireNotNull(SignalDatabase.threads.getRecipientForThreadId(messageRecord.threadId))
    return ConversationMessageFactory.createWithUnresolvedData(context, messageRecord, threadRecipient)
  }

  @Deprecated("Not needed for CFv2")
  fun loadDraftMessageEdit(serialized: String): Maybe<ConversationMessage> {
    return Maybe.fromCallable { loadDraftMessageEditInternal(serialized) }
  }

  private fun loadDraftMessageEditInternal(serialized: String): ConversationMessage? {
    val messageId = MessageId.deserialize(serialized)
    val messageRecord: MessageRecord = SignalDatabase.messages.getMessageRecordOrNull(messageId.id) ?: return null
    val threadRecipient: Recipient = requireNotNull(SignalDatabase.threads.getRecipientForThreadId(messageRecord.threadId))
    if (messageRecord.hasTextSlide()) {
      val textSlide = messageRecord.requireTextSlide()
      if (textSlide.uri != null) {
        try {
          PartAuthority.getAttachmentStream(context, textSlide.uri!!).use { stream ->
            val body = StreamUtil.readFullyAsString(stream)
            return ConversationMessageFactory.createWithUnresolvedData(context, messageRecord, body, threadRecipient)
          }
        } catch (e: IOException) {
          Log.e(TAG, "Failed to load text slide", e)
        }
      }
    }
    return ConversationMessageFactory.createWithUnresolvedData(context, messageRecord, threadRecipient)
  }

  @WorkerThread
  private fun getKeyboardImageDetails(glideRequests: GlideRequests, uri: Uri): KeyboardImageDetails? {
    return try {
      val bitmap: Bitmap = glideRequests.asBitmap()
        .load(DecryptableUri(uri))
        .skipMemoryCache(true)
        .diskCacheStrategy(DiskCacheStrategy.NONE)
        .submit()
        .get(1000, TimeUnit.MILLISECONDS)
      val topLeft = bitmap.getPixel(0, 0)
      KeyboardImageDetails(bitmap.width, bitmap.height, Color.alpha(topLeft) < 255)
    } catch (e: InterruptedException) {
      null
    } catch (e: ExecutionException) {
      null
    } catch (e: TimeoutException) {
      null
    }
  }

  data class DatabaseDraft(val drafts: Drafts, val updatedText: CharSequence?)

  sealed interface ShareOrDraftData {
    data class SendSticker(val slide: Slide) : ShareOrDraftData
    data class SendKeyboardImage(val slide: Slide) : ShareOrDraftData
    data class StartSendMedia(val mediaList: List<Media>, val text: CharSequence?) : ShareOrDraftData
    data class SetMedia(val media: Uri, val mediaType: SlideFactory.MediaType, val text: CharSequence?) : ShareOrDraftData
    data class SetText(val text: CharSequence) : ShareOrDraftData
    data class SetLocation(val location: SignalPlace, val draftText: CharSequence?) : ShareOrDraftData
    data class SetQuote(val quote: ConversationMessage, val draftText: CharSequence?) : ShareOrDraftData
    data class SetEditMessage(val messageEdit: ConversationMessage) : ShareOrDraftData
  }

  data class KeyboardImageDetails(val width: Int, val height: Int, val hasTransparency: Boolean)
}
