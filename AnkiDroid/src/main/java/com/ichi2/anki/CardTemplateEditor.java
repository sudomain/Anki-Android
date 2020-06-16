/***************************************************************************************
 *                                                                                      *
 * Copyright (c) 2014 Timothy Rae <perceptualchaos2@gmail.com>                          *
 * Copyright (c) 2018 Mike Hardy <mike@mikehardy.net>                                   *
 *                                                                                      *
 * This program is free software; you can redistribute it and/or modify it under        *
 * the terms of the GNU General Public License as published by the Free Software        *
 * Foundation; either version 3 of the License, or (at your option) any later           *
 * version.                                                                             *
 *                                                                                      *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY      *
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A      *
 * PARTICULAR PURPOSE. See the GNU General Public License for more details.             *
 *                                                                                      *
 * You should have received a copy of the GNU General Public License along with         *
 * this program.  If not, see <http://www.gnu.org/licenses/>.                           *
 ****************************************************************************************/

package com.ichi2.anki;

import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.os.Bundle;

import androidx.annotation.CheckResult;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.widget.ViewPager2;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;

import com.afollestad.materialdialogs.MaterialDialog;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;
import com.ichi2.anim.ActivityTransitionAnimation;
import com.ichi2.anki.dialogs.ConfirmationDialog;
import com.ichi2.anki.dialogs.DiscardChangesDialog;
import com.ichi2.anki.exception.ConfirmModSchemaException;
import com.ichi2.async.CollectionTask;
import com.ichi2.libanki.Collection;
import com.ichi2.libanki.Consts;
import com.ichi2.libanki.Models;
import com.ichi2.themes.StyledProgressDialog;

import com.ichi2.utils.JSONArray;
import com.ichi2.utils.JSONException;
import com.ichi2.utils.JSONObject;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import androidx.viewpager2.adapter.FragmentStateAdapter;
import timber.log.Timber;


/**
 * Allows the user to view the template for the current note type
 */
@SuppressWarnings({"PMD.AvoidThrowingRawExceptionTypes"})
public class CardTemplateEditor extends AnkiActivity {
    @VisibleForTesting
    protected ViewPager2 mViewPager;
    private TabLayout mSlidingTabLayout;
    private TemporaryModel mTempModel;
    private long mModelId;
    private long mNoteId;
    private int mStartingOrdId;
    private static final int REQUEST_PREVIEWER = 0;
    private static final int REQUEST_CARD_BROWSER_APPEARANCE = 1;


    // ----------------------------------------------------------------------------
    // Listeners
    // ----------------------------------------------------------------------------



    // ----------------------------------------------------------------------------
    // ANDROID METHODS
    // ----------------------------------------------------------------------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Timber.d("onCreate()");
        super.onCreate(savedInstanceState);
        setContentView(R.layout.card_template_editor_activity);
        // Load the args either from the intent or savedInstanceState bundle
        if (savedInstanceState == null) {
            // get model id
            mModelId = getIntent().getLongExtra("modelId", -1L);
            if (mModelId == -1) {
                Timber.e("CardTemplateEditor :: no model ID was provided");
                finishWithoutAnimation();
                return;
            }
            // get id for currently edited note (optional)
            mNoteId = getIntent().getLongExtra("noteId", -1L);
            // get id for currently edited template (optional)
            mStartingOrdId = getIntent().getIntExtra("ordId", -1);
        } else {
            mModelId = savedInstanceState.getLong("modelId");
            mNoteId = savedInstanceState.getLong("noteId");
            mStartingOrdId = savedInstanceState.getInt("ordId");
            mTempModel = TemporaryModel.fromBundle(savedInstanceState);
        }

        // Disable the home icon
        enableToolbar();
        startLoadingCollection();
    }


    @Override
    public void onSaveInstanceState(Bundle outState) {
        outState.putAll(getTempModel().toBundle());
        outState.putLong("modelId", mModelId);
        outState.putLong("noteId", mNoteId);
        outState.putInt("ordId", mStartingOrdId);
        super.onSaveInstanceState(outState);
    }

    @Override
    public void onBackPressed() {
        if (modelHasChanged()) {
            showDiscardChangesDialog();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case android.R.id.home: {
                onBackPressed();
                return true;
            }
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    /**
     * Callback used to finish initializing the activity after the collection has been correctly loaded
     * @param col Collection which has been loaded
     */
    @Override
    protected void onCollectionLoaded(Collection col) {
        super.onCollectionLoaded(col);
        // The first time the activity loads it has a model id but no edits yet, so no edited model
        // take the passed model id load it up for editing
        if (getTempModel() == null) {
            mTempModel = new TemporaryModel(new JSONObject(col.getModels().get(mModelId).toString()));
            //Timber.d("onCollectionLoaded() model is %s", mTempModel.getModel().toString(2));
        }
        // Set up the ViewPager with the sections adapter.
        mViewPager = findViewById(R.id.pager);
        mViewPager.setAdapter(new TemplatePagerAdapter((this)));
        mSlidingTabLayout = findViewById(R.id.sliding_tabs);
        // Set activity title
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(R.string.title_activity_template_editor);
            getSupportActionBar().setSubtitle(mTempModel.getModel().optString("name"));
        }
        // Close collection opening dialog if needed
        Timber.i("CardTemplateEditor:: Card template editor successfully started for model id %d", mModelId);

        // Set the tab to the current template if an ord id was provided
        Timber.d("Setting starting tab to %d", mStartingOrdId);
        if (mStartingOrdId != -1) {
            mViewPager.setCurrentItem(mStartingOrdId, animationDisabled());
        }
    }

    public boolean modelHasChanged() {
        JSONObject oldModel = getCol().getModels().get(mModelId);
        return getTempModel() != null && !getTempModel().getModel().toString().equals(oldModel.toString());
    }


    public TemporaryModel getTempModel() {
        return mTempModel;
    }

    @VisibleForTesting
    public MaterialDialog showDiscardChangesDialog() {
        MaterialDialog discardDialog = DiscardChangesDialog.getDefault(this)
                .onPositive((dialog, which) -> {
                    Timber.i("TemplateEditor:: OK button pressed to confirm discard changes");
                    // Clear the edited model from any cache files, and clear it from this objects memory to discard changes
                    TemporaryModel.clearTempModelFiles();
                    mTempModel = null;
                    finishWithAnimation(ActivityTransitionAnimation.RIGHT);
                })
                .build();
        discardDialog.show();
        return discardDialog;
    }


    // ----------------------------------------------------------------------------
    // INNER CLASSES
    // ----------------------------------------------------------------------------


    /**
     * A {@link androidx.viewpager2.adapter.FragmentStateAdapter} that returns a fragment corresponding to
     * one of the tabs.
     */
    public class TemplatePagerAdapter extends FragmentStateAdapter {

        private long baseId = 0;

        public TemplatePagerAdapter(@NonNull FragmentActivity fragmentActivity) {
            super(fragmentActivity);
        }


        @NonNull
        @Override
        public Fragment createFragment(int position) {
            return CardTemplateFragment.newInstance(position, mNoteId);
        }


        @Override
        public int getItemCount() {
            if (getTempModel() != null) {
                return getTempModel().getTemplateCount();
            }
            return 0;
        }


        @Override
        public long getItemId(int position) {
            return baseId + position;
        }


        @Override
        public boolean containsItem(long id) {
            return (id - baseId < getItemCount() && id - baseId >= 0);
        }

        /** Force fragments to reinitialize contents by invalidating previous set of ordinal-based ids */
        public void ordinalShift() {
            baseId += getItemCount() + 1;
        }
    }


    public static class CardTemplateFragment extends Fragment {
        private EditText mFront;
        private EditText mCss;
        private EditText mBack;
        private CardTemplateEditor mTemplateEditor;
        private TabLayoutMediator mTabLayoutMediator;

        public static CardTemplateFragment newInstance(int position, long noteId) {
            CardTemplateFragment f = new CardTemplateFragment();
            Bundle args = new Bundle();
            args.putInt("position", position);
            args.putLong("noteId",noteId);
            f.setArguments(args);
            return f;
        }

        @Override
        public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
            // Storing a reference to the templateEditor allows us to use member variables
            mTemplateEditor = (CardTemplateEditor)getActivity();
            View mainView = inflater.inflate(R.layout.card_template_editor_item, container, false);
            final int position = getArguments().getInt("position");
            TemporaryModel tempModel = mTemplateEditor.getTempModel();
            // Load template
            final JSONObject template;
            try {
                template = tempModel.getTemplate(position);
            } catch (JSONException e) {
                Timber.d(e, "Exception loading template in CardTemplateFragment. Probably stale fragment.");
                return mainView;
            }
            // Load EditText Views
            mFront = ((EditText) mainView.findViewById(R.id.front_edit));
            mCss = ((EditText) mainView.findViewById(R.id.styling_edit));
            mBack = ((EditText) mainView.findViewById(R.id.back_edit));
            // Set EditText content
            mFront.setText(template.getString("qfmt"));
            mCss.setText(tempModel.getCss());
            mBack.setText(template.getString("afmt"));
            // Set text change listeners
            TextWatcher templateEditorWatcher = new TextWatcher() {
                @Override
                public void afterTextChanged(Editable arg0) {
                    template.put("qfmt", mFront.getText());
                    template.put("afmt", mBack.getText());
                    mTemplateEditor.getTempModel().updateCss(mCss.getText().toString());
                    mTemplateEditor.getTempModel().updateTemplate(position, template);
                }


                @Override
                public void beforeTextChanged(CharSequence arg0, int arg1, int arg2, int arg3) { /* do nothing */ }


                @Override
                public void onTextChanged(CharSequence arg0, int arg1, int arg2, int arg3) { /* do nothing */ }
            };
            mFront.addTextChangedListener(templateEditorWatcher);
            mCss.addTextChangedListener(templateEditorWatcher);
            mBack.addTextChangedListener(templateEditorWatcher);
            // Enable menu
            setHasOptionsMenu(true);
            return mainView;
        }


        @Override
        public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
            initTabLayoutMediator();
        }

        private void initTabLayoutMediator() {
            if (mTabLayoutMediator != null) {
                mTabLayoutMediator.detach();
            }
            mTabLayoutMediator = new TabLayoutMediator(mTemplateEditor.mSlidingTabLayout, mTemplateEditor.mViewPager,
                    (tab, position) -> tab.setText(mTemplateEditor.getTempModel().getTemplate(position).getString("name"))
            );
             mTabLayoutMediator.attach();
        }


        @Override
        public void onResume() {
            //initTabLayoutMediator();
            super.onResume();
        }


        @Override
        public void onCreateOptionsMenu(@NonNull Menu menu, MenuInflater inflater) {
            menu.clear();
            inflater.inflate(R.menu.card_template_editor, menu);

            if (mTemplateEditor.getTempModel().getModel().getInt("type") == Consts.MODEL_CLOZE) {
                Timber.d("Editing cloze model, disabling add/delete card template functionality");
                menu.findItem(R.id.action_add).setVisible(false);
            }

            // It is invalid to reposition or delete if there is only one card template, remove the option from UI
            if (mTemplateEditor.getTempModel().getTemplateCount() < 2) {
                menu.findItem(R.id.action_delete).setVisible(false);
                //menu.findItem(R.id.action_reposition).setVisible(false);
            }

            super.onCreateOptionsMenu(menu, inflater);
        }


        @Override
        public boolean onOptionsItemSelected(MenuItem item) {
            final Collection col = mTemplateEditor.getCol();
            TemporaryModel tempModel = mTemplateEditor.getTempModel();
            switch (item.getItemId()) {
                case R.id.action_add:
                    Timber.i("CardTemplateEditor:: Add template button pressed");
                    // TODO in Anki Desktop, they have a popup first with "This will create %d cards. Proceed?"
                    //      AnkiDroid never had this so it isn't a regression but it is a miss for AnkiDesktop parity
                    addNewTemplateWithCheck(tempModel.getModel());
                    return true;
                case R.id.action_delete: {
                    Timber.i("CardTemplateEditor:: Delete template button pressed");
                    Resources res = getResources();
                    int ordinal = mTemplateEditor.mViewPager.getCurrentItem();
                    final JSONObject template = tempModel.getTemplate(ordinal);
                    // Don't do anything if only one template
                    if (tempModel.getTemplateCount() < 2) {
                        mTemplateEditor.showSimpleMessageDialog(res.getString(R.string.card_template_editor_cant_delete));
                        return true;
                    }

                    if (deletionWouldOrphanNote(col, tempModel, ordinal)) {
                        return true;
                    }

                    // Show confirmation dialog
                    int numAffectedCards = 0;
                    if (!TemporaryModel.isOrdinalPendingAdd(tempModel, ordinal)) {
                        Timber.d("Ordinal is not a pending add, so we'll get the current card count for confirmation");
                        numAffectedCards = col.getModels().tmplUseCount(tempModel.getModel(), ordinal);
                    }
                    confirmDeleteCards(template, tempModel.getModel(), numAffectedCards);
                    return true;
                }
                case R.id.action_reposition: {
                    Timber.i("CardTemplateEditor:: Reposition template button pressed");
                    Resources res = getResources();
                    //TODO reposition and remove use the same code for checking for one template.
                    int ordinal = mTemplateEditor.mViewPager.getCurrentItem();
                    final JSONObject template = tempModel.getTemplate(ordinal);
                    // Don't do anything if only one template
                    if (tempModel.getTemplateCount() < 2) {
                        mTemplateEditor.showSimpleMessageDialog(res.getString(R.string.card_template_editor_cant_reposition));
                        return true;
                    }
                    promptForNewPosition(tempModel.getModel());

                    return true;
                }
                case R.id.action_preview: {
                    Timber.i("CardTemplateEditor:: Preview on tab %s", mTemplateEditor.mViewPager.getCurrentItem());
                    // Create intent for the previewer and add some arguments
                    Intent i = new Intent(mTemplateEditor, CardTemplatePreviewer.class);
                    int ordinal = mTemplateEditor.mViewPager.getCurrentItem();
                    long noteId = getArguments().getLong("noteId");
                    i.putExtra("ordinal", ordinal);

                    // If we have a card for this position, send it, otherwise an empty cardlist signals to show a blank
                    if (noteId != -1L && ordinal < col.getNote(noteId).cards().size()) {
                        i.putExtra("cardList", new long[] { col.getNote(noteId).cards().get(ordinal).getId() });
                    }
                    // Save the model and pass the filename if updated
                    tempModel.setEditedModelFileName(TemporaryModel.saveTempModel(mTemplateEditor, tempModel.getModel()));
                    i.putExtra(TemporaryModel.INTENT_MODEL_FILENAME, tempModel.getEditedModelFileName());
                    startActivityForResult(i, REQUEST_PREVIEWER);
                    return true;
                }
                case R.id.action_confirm:
                    Timber.i("CardTemplateEditor:: Save model button pressed");
                    if (modelHasChanged()) {
                        View confirmButton = mTemplateEditor.findViewById(R.id.action_confirm);
                        if (confirmButton != null) {
                            if (!confirmButton.isEnabled()) {
                                Timber.d("CardTemplateEditor::discarding extra click after button disabled");
                                return true;
                            }
                            confirmButton.setEnabled(false);
                        }
                        tempModel.saveToDatabase(mSaveModelAndExitHandler);
                    } else {
                        Timber.d("CardTemplateEditor:: model has not changed, exiting");
                        mTemplateEditor.finishWithAnimation(ActivityTransitionAnimation.RIGHT);
                    }

                    return true;
                case R.id.action_card_browser_appearance:
                    Timber.i("CardTemplateEditor::Card Browser Template button pressed");
                    launchCardBrowserAppearance(getCurrentTemplate());
                default:
                    return super.onOptionsItemSelected(item);
            }
        }


        private void launchCardBrowserAppearance(JSONObject currentTemplate) {
            Context context = AnkiDroidApp.getInstance().getBaseContext();
            if (context == null) {
                //Catch-22, we can't notify failure as there's no context. Shouldn't happen anyway
                Timber.w("Context was null - couldn't launch Card Browser Appearance window");
                return;
            }
            Intent browserAppearanceIntent = CardTemplateBrowserAppearanceEditor.getIntentFromTemplate(context, currentTemplate);
            startActivityForResult(browserAppearanceIntent, REQUEST_CARD_BROWSER_APPEARANCE);
        }


        @CheckResult @NonNull
        private JSONObject getCurrentTemplate() {
            int currentCardTemplateIndex = getCurrentCardTemplateIndex();
            return (JSONObject) mTemplateEditor.getTempModel().getModel().getJSONArray("tmpls").get(currentCardTemplateIndex);
        }


        /**
         * @return The index of the card template which is currently referred to by the fragment
         */
        @CheckResult
        private int getCurrentCardTemplateIndex() {
            //COULD_BE_BETTER: Lots of duplicate code could call this. Hold off on the refactor until #5151 goes in.
            return getArguments().getInt("position");
        }


        private boolean deletionWouldOrphanNote(Collection col, TemporaryModel tempModel, int position) {
            // For existing templates, make sure we won't leave orphaned notes if we delete the template
            //
            // Note: we are in-memory, so the database is unaware of previous but unsaved deletes.
            // If we were deleting a template we just added, we don't care. If not, then for every
            // template delete queued up, we check the database to see if this delete in combo with any other
            // pending deletes could orphan cards
            if (!TemporaryModel.isOrdinalPendingAdd(tempModel, position)) {
                int[] currentDeletes = tempModel.getDeleteDbOrds(position);
                // TODO - this is a SQL query on GUI thread - should see a DeckTask conversion ideally
                if (col.getModels().getCardIdsForModel(tempModel.getModelId(), currentDeletes) == null) {

                    // It is possible but unlikely that a user has an in-memory template addition that would
                    // generate cards making the deletion safe, but we don't handle that. All users who do
                    // not already have cards generated making it safe will see this error message:
                    mTemplateEditor.showSimpleMessageDialog(getResources().getString(R.string.card_template_editor_would_delete_note));
                    return true;
                }
            }
            return false;
        }


        @Override
        public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
            super.onActivityResult(requestCode, resultCode, data);
            if (requestCode == REQUEST_CARD_BROWSER_APPEARANCE) {
                onCardBrowserAppearanceResult(resultCode, data);
                return;
            }

            if (requestCode == REQUEST_PREVIEWER) {
                TemporaryModel.clearTempModelFiles();
                // Make sure the fragments reinitialize, otherwise there is staleness on return
                ((TemplatePagerAdapter)mTemplateEditor.mViewPager.getAdapter()).ordinalShift();
                mTemplateEditor.mViewPager.getAdapter().notifyDataSetChanged();
            }
        }


        private void onCardBrowserAppearanceResult(int resultCode, @Nullable Intent data) {
            if (resultCode != RESULT_OK) {
                Timber.i("Activity Cancelled: Card Template Browser Appearance");
                return;
            }

            CardTemplateBrowserAppearanceEditor.Result result = CardTemplateBrowserAppearanceEditor.Result.fromIntent(data);
            if (result == null) {
                Timber.w("Error processing Card Template Browser Appearance result");
                return;
            }

            Timber.i("Applying Card Template Browser Appearance result");

            JSONObject currentTemplate = getCurrentTemplate();
            result.applyTo(currentTemplate);
        }

        /* Used for updating the collection when a model has been edited */
        private CollectionTask.TaskListener mSaveModelAndExitHandler = new CollectionTask.TaskListener() {
            private MaterialDialog mProgressDialog = null;
            @Override
            public void onPreExecute() {
                Timber.d("mSaveModelAndExitHandler::preExecute called");
                mProgressDialog = StyledProgressDialog.show(mTemplateEditor, AnkiDroidApp.getAppResources().getString(R.string.saving_model),
                        getResources().getString(R.string.saving_changes), false);
            }

            @Override
            public void onPostExecute(CollectionTask.TaskData result) {
                Timber.d("mSaveModelAndExitHandler::postExecute called");
                View button = mTemplateEditor.findViewById(R.id.action_confirm);
                if (button != null) {
                    button.setEnabled(true);
                }
                if (mProgressDialog != null && mProgressDialog.isShowing()) {
                    mProgressDialog.dismiss();
                }
                mTemplateEditor.mTempModel = null;
                if (result.getBoolean()) {
                    mTemplateEditor.finishWithAnimation(ActivityTransitionAnimation.RIGHT);
                } else {
                    Timber.w("CardTemplateEditor:: save model task failed: %s", result.getString());
                    UIUtils.showThemedToast(mTemplateEditor, mTemplateEditor.getString(R.string.card_template_editor_save_error, result.getString()), false);
                    mTemplateEditor.finishWithoutAnimation();
                }
            }
        };

        private boolean modelHasChanged() {
            return mTemplateEditor.modelHasChanged();
        }

        /**
         * Confirm if the user wants to delete all the cards associated with current template
         *
         * @param tmpl template to remove
         * @param model model to remove template from, modified in place by reference
         * @param numAffectedCards number of cards which will be affected
         */
        private void confirmDeleteCards(final JSONObject tmpl, final JSONObject model,  int numAffectedCards) {
            ConfirmationDialog d = new ConfirmationDialog();
            Resources res = getResources();
            String msg = String.format(res.getQuantityString(R.plurals.card_template_editor_confirm_delete,
                            numAffectedCards), numAffectedCards, tmpl.optString("name"));
            d.setArgs(msg);
            Runnable confirm = new Runnable() {
                @Override
                public void run() {
                    deleteTemplateWithCheck(tmpl, model);
                }
            };
            d.setConfirm(confirm);
            mTemplateEditor.showDialogFragment(d);
        }

        /**
         * Delete tmpl from model, asking user to confirm again if it's going to require a full sync
         *
         * @param tmpl template to remove
         * @param model model to remove template from, modified in place by reference
         */
        private void deleteTemplateWithCheck(final JSONObject tmpl, final JSONObject model) {
            try {
                mTemplateEditor.getCol().modSchema();
                deleteTemplate(tmpl, model);
            } catch (ConfirmModSchemaException e) {
                ConfirmationDialog d = new ConfirmationDialog();
                d.setArgs(getResources().getString(R.string.full_sync_confirmation));
                Runnable confirm = () -> {
                    mTemplateEditor.getCol().modSchemaNoCheck();
                    deleteTemplate(tmpl, model);
                };
                Runnable cancel = () -> mTemplateEditor.dismissAllDialogFragments();
                d.setConfirm(confirm);
                d.setCancel(cancel);
                mTemplateEditor.showDialogFragment(d);
            }
        }

        /**
         * @param tmpl template to remove
         * @param model model to remove from, updated in place by reference
         */
        private void deleteTemplate(JSONObject tmpl, JSONObject model) {
            JSONArray oldTemplates = model.getJSONArray("tmpls");
            JSONArray newTemplates = new JSONArray();
            for (int i = 0; i < oldTemplates.length(); i++) {
                JSONObject possibleMatch = oldTemplates.getJSONObject(i);
                if (possibleMatch.getInt("ord") != tmpl.getInt("ord")) {
                    newTemplates.put(possibleMatch);
                } else {
                    Timber.d("deleteTemplate() found match - removing template with ord %s", possibleMatch.getInt("ord"));
                    mTemplateEditor.getTempModel().removeTemplate(possibleMatch.getInt("ord"));
                }
            }
            model.put("tmpls", newTemplates);
            Models._updateTemplOrds(model);
            // Make sure the fragments reinitialize, otherwise the reused ordinal causes staleness
            ((TemplatePagerAdapter)mTemplateEditor.mViewPager.getAdapter()).ordinalShift();
            mTemplateEditor.mViewPager.getAdapter().notifyDataSetChanged();
            mTemplateEditor.mViewPager.setCurrentItem(newTemplates.length() - 1, mTemplateEditor.animationDisabled());

            if (getActivity() != null) {
                ((CardTemplateEditor) getActivity()).dismissAllDialogFragments();
            }
        }

        /**
         * Add new template to model, asking user to confirm if it's going to require a full sync
         *
         * @param model model to add new template to
         */
        private void addNewTemplateWithCheck(final JSONObject model) {
            try {
                mTemplateEditor.getCol().modSchema();
                Timber.d("addNewTemplateWithCheck() called and no CMSE?");
                addNewTemplate(model);
            } catch (ConfirmModSchemaException e) {
                ConfirmationDialog d = new ConfirmationDialog();
                d.setArgs(getResources().getString(R.string.full_sync_confirmation));
                Runnable confirm = () -> {
                    mTemplateEditor.getCol().modSchemaNoCheck();
                    addNewTemplate(model);
                };
                d.setConfirm(confirm);
                mTemplateEditor.showDialogFragment(d);
            }
        }


        /**
         * Add new template to a given model
         * @param model model to add new template to
         */
        private void addNewTemplate(JSONObject model) {
            // Build new template
            JSONObject newTemplate;
            int oldPosition = getArguments().getInt("position");
            JSONArray templates = model.getJSONArray("tmpls");
            JSONObject oldTemplate = templates.getJSONObject(oldPosition);
            newTemplate = Models.newTemplate(newCardName(templates));
            // Set up question & answer formats
            newTemplate.put("qfmt", oldTemplate.get("qfmt"));
            newTemplate.put("afmt", oldTemplate.get("afmt"));
            // Reverse the front and back if only one template
            if (templates.length() == 1) {
                flipQA(newTemplate);
            }

            int lastExistingOrd = templates.getJSONObject(templates.length() - 1).getInt("ord");
            Timber.d("addNewTemplate() lastExistingOrd was %s", lastExistingOrd);
            newTemplate.put("ord", lastExistingOrd + 1);
            templates.put(newTemplate);
            mTemplateEditor.getTempModel().addNewTemplate(newTemplate);
            mTemplateEditor.mViewPager.getAdapter().notifyDataSetChanged();
            mTemplateEditor.mViewPager.setCurrentItem(templates.length() - 1, mTemplateEditor.animationDisabled());
        }


        private void promptForNewPosition(JSONObject model){
            //get number of templates from the temporary model
            int numTemplates = model.getJSONArray("tmpls").length();
            //get int from user "Enter new card position (1...numTemplates):"
            EditText mFieldNameInput = new EditText(getContext());
                mFieldNameInput.setRawInputType(InputType.TYPE_CLASS_NUMBER);
                new MaterialDialog.Builder(getContext())
                        .title(String.format(getResources().getString(R.string.model_field_editor_reposition), 1, numTemplates))
                        .positiveText(R.string.dialog_ok)
                        .customView(mFieldNameInput, true)
                        .onPositive((dialog, which) -> {
                            String newPosition = mFieldNameInput.getText().toString();
                            int pos;
                            try {
                                pos = Integer.parseInt(newPosition);
                            } catch (NumberFormatException n) {
                                UIUtils.showThemedToast(getContext(), getResources().getString(R.string.toast_out_of_range), true);
                                return;
                            }

                            if (pos < 1 || pos > numTemplates) {
                                UIUtils.showThemedToast(getContext(), getResources().getString(R.string.toast_out_of_range), true);
                            }

                        })
                        .negativeText(R.string.dialog_cancel)
                        .show();
            //TODO call reposition template?
        }

        /**
         * @param tmpl template to reposition
         * @param model model to in which the template will reposition, updated in place by reference
         */
        private void repositionTemplate(JSONObject tmpl, JSONObject model) {
            JSONArray oldTemplates = model.getJSONArray("tmpls");
            JSONArray newTemplates = new JSONArray();
            for (int i = 0; i < oldTemplates.length(); i++) {
                JSONObject possibleMatch = oldTemplates.getJSONObject(i);
                if (possibleMatch.getInt("ord") != tmpl.getInt("ord")) {
                    newTemplates.put(possibleMatch);
                } else {
                    Timber.d("deleteTemplate() found match - removing template with ord %s", possibleMatch.getInt("ord"));
                    mTemplateEditor.getTempModel().removeTemplate(possibleMatch.getInt("ord"));
                }
            }
            model.put("tmpls", newTemplates);
            Models._updateTemplOrds(model);
            // Make sure the fragments reinitialize, otherwise the reused ordinal causes staleness
            ((TemplatePagerAdapter)mTemplateEditor.mViewPager.getAdapter()).ordinalShift();
            mTemplateEditor.mViewPager.getAdapter().notifyDataSetChanged();
            mTemplateEditor.mViewPager.setCurrentItem(newTemplates.length() - 1, mTemplateEditor.animationDisabled());

            if (getActivity() != null) {
                ((CardTemplateEditor) getActivity()).dismissAllDialogFragments();
            }
        }


        /**
         * Flip the question and answer side of the template
         * @param template template to flip
         */
        private void flipQA (JSONObject template) {
            String qfmt = template.getString("qfmt");
            String afmt = template.getString("afmt");
            Matcher m = Pattern.compile("(?s)(.+)<hr id=answer>(.+)").matcher(afmt);
            if (!m.find()) {
                template.put("qfmt", afmt.replace("{{FrontSide}}",""));
            } else {
                template.put("qfmt",m.group(2).trim());
            }
            template.put("afmt","{{FrontSide}}\n\n<hr id=answer>\n\n" + qfmt);
        }

        /**
         * Get name for new template
         * @param templates array of templates which is being added to
         * @return name for new template
         */
        private String newCardName(JSONArray templates) {
            String name;
            // Start by trying to set the name to "Card n" where n is the new num of templates
            int n = templates.length() + 1;
            // If the starting point for name already exists, iteratively increase n until we find a unique name
            while (true) {
                // Get new name
                name = "Card " + Integer.toString(n);
                // Cycle through all templates checking if new name exists
                boolean exists = false;
                for (int i = 0; i < templates.length(); i++) {
                    exists = exists || name.equals(templates.getJSONObject(i).getString("name"));
                }
                if (!exists) {
                    break;
                }
                n+=1;
            }
            return name;
        }
    }
}
