package org.thoughtcrime.securesms.mediapreview

import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import org.thoughtcrime.securesms.attachments.Attachment
import org.thoughtcrime.securesms.util.MediaUtil

class MediaPreviewV2Adapter(val fragment: Fragment) : FragmentStateAdapter(fragment) {
  private var items: List<Attachment> = listOf()
  private var autoPlayPosition = -1

  override fun getItemCount(): Int {
    return items.count()
  }

  override fun createFragment(position: Int): Fragment {
    val attachment: Attachment = items[position]

    val contentType = attachment.contentType
    val args = bundleOf(
      MediaPreviewFragment.DATA_URI to attachment.uri,
      MediaPreviewFragment.DATA_CONTENT_TYPE to contentType,
      MediaPreviewFragment.DATA_SIZE to attachment.size,
      MediaPreviewFragment.AUTO_PLAY to (position == autoPlayPosition),
      MediaPreviewFragment.VIDEO_GIF to attachment.isVideoGif,
    )
    val fragment = if (MediaUtil.isVideo(contentType)) {
      VideoMediaPreviewFragment()
    } else if (MediaUtil.isImageType(contentType)) {
      ImageMediaPreviewFragment()
    } else {
      throw AssertionError("Unexpected media type: $contentType")
    }

    fragment.arguments = args

    return fragment
  }

  fun updateBackingItems(newItems: Collection<Attachment>) {
    if (newItems != items) {
      items = newItems.toList()
      notifyDataSetChanged()
    }
  }
}
