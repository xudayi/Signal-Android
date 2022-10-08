/*
 * Copyright (C) 2015 Open Whisper Systems
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.thoughtcrime.securesms;

import android.content.Intent;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.ViewGroup;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AlertDialog;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;

import org.signal.core.util.DimensionUnit;
import org.signal.core.util.concurrent.SimpleTask;
import org.signal.core.util.logging.Log;
import org.thoughtcrime.securesms.components.menu.ActionItem;
import org.thoughtcrime.securesms.components.menu.SignalContextMenu;
import org.thoughtcrime.securesms.contacts.ContactSelectionListItem;
import org.thoughtcrime.securesms.contacts.management.ContactsManagementRepository;
import org.thoughtcrime.securesms.contacts.management.ContactsManagementViewModel;
import org.thoughtcrime.securesms.contacts.sync.ContactDiscovery;
import org.thoughtcrime.securesms.conversation.ConversationIntents;
import org.thoughtcrime.securesms.database.SignalDatabase;
import org.thoughtcrime.securesms.groups.ui.creategroup.CreateGroupActivity;
import org.thoughtcrime.securesms.jobmanager.impl.NetworkConstraint;
import org.thoughtcrime.securesms.keyvalue.SignalStore;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.recipients.RecipientId;
import org.thoughtcrime.securesms.util.CommunicationActions;
import org.thoughtcrime.securesms.util.FeatureFlags;
import org.thoughtcrime.securesms.util.LifecycleDisposable;
import org.thoughtcrime.securesms.util.views.SimpleProgressDialog;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Activity container for starting a new conversation.
 *
 * @author Moxie Marlinspike
 */
public class NewConversationActivity extends ContactSelectionActivity
    implements ContactSelectionListFragment.ListCallback, ContactSelectionListFragment.OnItemLongClickListener
{

  @SuppressWarnings("unused")
  private static final String TAG = Log.tag(NewConversationActivity.class);

  private ContactsManagementViewModel    viewModel;
  private ActivityResultLauncher<Intent> contactLauncher;

  private final LifecycleDisposable disposables = new LifecycleDisposable();

  @Override
  public void onCreate(Bundle bundle, boolean ready) {
    super.onCreate(bundle, ready);
    assert getSupportActionBar() != null;
    getSupportActionBar().setDisplayHomeAsUpEnabled(true);
    getSupportActionBar().setTitle(R.string.NewConversationActivity__new_message);

    disposables.bindTo(this);

    ContactsManagementRepository        repository = new ContactsManagementRepository(this);
    ContactsManagementViewModel.Factory factory    = new ContactsManagementViewModel.Factory(repository);

    contactLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), activityResult -> {
      if (activityResult.getResultCode() == RESULT_OK) {
        handleManualRefresh();
      }
    });

    viewModel = new ViewModelProvider(this, factory).get(ContactsManagementViewModel.class);
  }

  @Override
  public void onBeforeContactSelected(@NonNull Optional<RecipientId> recipientId, String number, @NonNull Consumer<Boolean> callback) {
    if (recipientId.isPresent()) {
      launch(Recipient.resolved(recipientId.get()));
    } else {
      Log.i(TAG, "[onContactSelected] Maybe creating a new recipient.");

      if (SignalStore.account().isRegistered() && NetworkConstraint.isMet(getApplication())) {
        Log.i(TAG, "[onContactSelected] Doing contact refresh.");

        AlertDialog progress = SimpleProgressDialog.show(this);

        SimpleTask.run(getLifecycle(), () -> {
          Recipient resolved = Recipient.external(this, number);

          if (!resolved.isRegistered() || !resolved.hasServiceId()) {
            Log.i(TAG, "[onContactSelected] Not registered or no UUID. Doing a directory refresh.");
            try {
              ContactDiscovery.refresh(this, resolved, false);
              resolved = Recipient.resolved(resolved.getId());
            } catch (IOException e) {
              Log.w(TAG, "[onContactSelected] Failed to refresh directory for new contact.");
            }
          }

          return resolved;
        }, resolved -> {
          progress.dismiss();
          launch(resolved);
        });
      } else {
        launch(Recipient.external(this, number));
      }
    }

    callback.accept(true);
  }

  @Override
  public void onSelectionChanged() {
  }

  private void launch(Recipient recipient) {
    long   existingThread = SignalDatabase.threads().getThreadIdIfExistsFor(recipient.getId());
    Intent intent         = ConversationIntents.createBuilder(this, recipient.getId(), existingThread)
                                               .withDraftText(getIntent().getStringExtra(Intent.EXTRA_TEXT))
                                               .withDataUri(getIntent().getData())
                                               .withDataType(getIntent().getType())
                                               .build();

    startActivity(intent);
    finish();
  }

  @Override
  public boolean onOptionsItemSelected(MenuItem item) {
    super.onOptionsItemSelected(item);

    switch (item.getItemId()) {
      case android.R.id.home:
        super.onBackPressed();
        return true;
      case R.id.menu_refresh:
        handleManualRefresh();
        return true;
      case R.id.menu_new_group:
        handleCreateGroup();
        return true;
      case R.id.menu_invite:
        handleInvite();
        return true;
    }

    return false;
  }

  private void handleManualRefresh() {
    contactsFragment.setRefreshing(true);
    onRefresh();
  }

  private void handleCreateGroup() {
    startActivity(CreateGroupActivity.newIntent(this));
  }

  private void handleInvite() {
    startActivity(new Intent(this, InviteActivity.class));
  }

  @Override
  public boolean onCreateOptionsMenu(Menu menu) {
    menu.clear();
    getMenuInflater().inflate(R.menu.new_conversation_activity, menu);

    super.onCreateOptionsMenu(menu);
    return true;
  }

  @Override
  public void onInvite() {
    handleInvite();
    finish();
  }

  @Override
  public void onNewGroup(boolean forceV1) {
    handleCreateGroup();
    finish();
  }

  @Override
  public boolean onLongClick(ContactSelectionListItem contactSelectionListItem) {
    RecipientId recipientId = contactSelectionListItem.getRecipientId().orElse(null);
    if (recipientId == null) {
      return false;
    }

    List<ActionItem> actions = generateContextualActionsForRecipient(recipientId);
    if (actions.isEmpty()) {
      return false;
    }

    new SignalContextMenu.Builder(contactSelectionListItem, (ViewGroup) contactSelectionListItem.getRootView())
        .preferredVerticalPosition(SignalContextMenu.VerticalPosition.BELOW)
        .preferredHorizontalPosition(SignalContextMenu.HorizontalPosition.START)
        .offsetX((int) DimensionUnit.DP.toPixels(12))
        .offsetY((int) DimensionUnit.DP.toPixels(12))
        .show(actions);

    return true;
  }

  private @NonNull List<ActionItem> generateContextualActionsForRecipient(@NonNull RecipientId recipientId) {
    Recipient recipient = Recipient.resolved(recipientId);

    return Stream.of(
        createMessageActionItem(recipient),
        createAudioCallActionItem(recipient),
        createVideoCallActionItem(recipient),
        createRemoveActionItem(recipient),
        createBlockActionItem(recipient)
    ).filter(Objects::nonNull).collect(Collectors.toList());
  }

  private @NonNull ActionItem createMessageActionItem(@NonNull Recipient recipient) {
    return new ActionItem(
        R.drawable.ic_chat_message_24,
        getString(R.string.NewConversationActivity__message),
        R.color.signal_colorOnSurface,
        () -> startActivity(ConversationIntents.createBuilder(this, recipient.getId(), -1L).build())
    );
  }

  private @Nullable ActionItem createAudioCallActionItem(@NonNull Recipient recipient) {
    if (recipient.isSelf() || recipient.isGroup()) {
      return null;
    }

    return new ActionItem(
        R.drawable.ic_phone_right_24,
        getString(R.string.NewConversationActivity__audio_call),
        R.color.signal_colorOnSurface,
        () -> CommunicationActions.startVoiceCall(this, recipient)
    );
  }

  private @Nullable ActionItem createVideoCallActionItem(@NonNull Recipient recipient) {
    if (recipient.isSelf() || recipient.isMmsGroup()) {
      return null;
    }

    return new ActionItem(
        R.drawable.ic_video_call_24,
        getString(R.string.NewConversationActivity__video_call),
        R.color.signal_colorOnSurface,
        () -> CommunicationActions.startVideoCall(this, recipient)
    );
  }

  private @Nullable ActionItem createRemoveActionItem(@NonNull Recipient recipient) {
    if (!FeatureFlags.hideContacts() || recipient.isSelf() || recipient.isGroup()) {
      return null;
    }

    return new ActionItem(
        R.drawable.ic_minus_circle_20, // TODO [alex] -- correct asset
        getString(R.string.NewConversationActivity__remove),
        R.color.signal_colorOnSurface,
        () -> {
          if (recipient.isSystemContact()) {
            displayIsInSystemContactsDialog(recipient);
          } else {
            displayRemovalDialog(recipient);
          }
        }
    );
  }

  @SuppressWarnings("CodeBlock2Expr")
  private @Nullable ActionItem createBlockActionItem(@NonNull Recipient recipient) {
    if (recipient.isSelf()) {
      return null;
    }

    return new ActionItem(
        R.drawable.ic_block_tinted_24,
        getString(R.string.NewConversationActivity__block),
        R.color.signal_colorError,
        () -> BlockUnblockDialog.showBlockFor(this,
                                              this.getLifecycle(),
                                              recipient,
                                              () -> {
                                                disposables.add(viewModel.blockContact(recipient).subscribe(() -> {
                                                  displaySnackbar(R.string.NewConversationActivity__s_has_been_removed);
                                                }));
                                              })
    );
  }

  private void displayIsInSystemContactsDialog(@NonNull Recipient recipient) {
    new MaterialAlertDialogBuilder(this)
        .setTitle(getString(R.string.NewConversationActivity__unable_to_remove_s, recipient.getShortDisplayName(this)))
        .setMessage(R.string.NewConversationActivity__this_person_is_saved_to_your)
        .setPositiveButton(R.string.NewConversationActivity__view_contact,
                           (dialog, which) -> contactLauncher.launch(new Intent(Intent.ACTION_VIEW, recipient.getContactUri()))
        )
        .setNegativeButton(android.R.string.cancel, null)
        .show();
  }

  private void displayRemovalDialog(@NonNull Recipient recipient) {
    new MaterialAlertDialogBuilder(this)
        .setTitle(getString(R.string.NewConversationActivity__remove_s, recipient.getShortDisplayName(this)))
        .setMessage(R.string.NewConversationActivity__you_wont_see_this_person)
        .setPositiveButton(R.string.NewConversationActivity__remove,
                           (dialog, which) -> {
                             disposables.add(viewModel.hideContact(recipient).subscribe(() -> {
                               displaySnackbar(R.string.NewConversationActivity__s_has_been_removed);
                             }));
                           }
        )
        .setNegativeButton(android.R.string.cancel, null)
        .show();
  }

  private void displaySnackbar(@StringRes int message) {
    Snackbar.make(findViewById(android.R.id.content), message, Snackbar.LENGTH_SHORT).show();
  }
}
