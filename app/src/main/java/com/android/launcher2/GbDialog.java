/*
 * Copyright (C) 2008 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.launcher2;

import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.res.Configuration;
import android.util.DisplayMetrics;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ListAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import com.android.launcher.R;

/**
 * Tweaks: the one Gingerbread dialog of the launcher (settings, pickers, rename folder, weather
 * widget). Replaces android.app.AlertDialog, whose look depends on the system theme. Frame
 * backgrounds, buttons, lists and fields are the Gingerbread resources (gb_*), the panel
 * logic follows AlertController of android-2.3.7_r1.
 */
public class GbDialog extends Dialog {
    private static final int MODE_PLAIN = 0;
    private static final int MODE_SINGLE = 1;
    private static final int MODE_MULTI = 2;

    private int mIcon;
    private CharSequence mTitle;
    private CharSequence mMessage;
    private View mView;
    private ListAdapter mAdapter;
    private int mMode = MODE_PLAIN;
    private int mChecked = -1;
    private boolean[] mMultiChecked;
    private DialogInterface.OnClickListener mItemListener;
    private DialogInterface.OnMultiChoiceClickListener mMultiListener;
    private boolean mInverse;
    private ListView mListView;

    // index 0: positive, 1: negative, 2: neutral
    private final CharSequence[] mButtonText = new CharSequence[3];
    private final DialogInterface.OnClickListener[] mButtonListener =
            new DialogInterface.OnClickListener[3];

    /** The Gingerbread dialog theme on top of the given context, for views put into a dialog. */
    public static Context themed(Context context) {
        return new ContextThemeWrapper(context, R.style.Theme_GbDialog);
    }

    /** Text field as in a Gingerbread dialog (use it for setView()). */
    public static EditText newEditText(Context context) {
        final EditText input = new EditText(themed(context));
        input.setTextSize(18);
        return input;
    }

    /** Field wrapper with the margins of Gingerbread's dialog content. */
    public static View wrapField(Context context, View field) {
        final int pad = (int) (6 * context.getResources().getDisplayMetrics().density);
        final FrameLayout frame = new FrameLayout(context);
        frame.setPadding(pad, 0, pad, 0);
        frame.addView(field);
        return frame;
    }

    /** Gingerbread's indeterminate progress dialog (ProgressDialog.show(..., true, false)). */
    public static GbDialog showProgress(Context context, CharSequence message) {
        final View v = LayoutInflater.from(themed(context)).inflate(R.layout.gb_progress_dialog,
                null);
        ((TextView) v.findViewById(R.id.gb_message)).setText(message);
        final GbDialog dialog = new Builder(context).setView(v).setCancelable(false).create();
        dialog.show();
        return dialog;
    }

    private GbDialog(Context context) {
        super(context, R.style.Theme_GbDialog);
    }

    public ListView getListView() {
        return mListView;
    }

    @Override
    protected void onStart() {
        super.onStart();
        // WeightedLinearLayout of the original: 90% (portrait) / 65% (landscape) of the width
        final DisplayMetrics dm = getContext().getResources().getDisplayMetrics();
        final boolean land = getContext().getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_LANDSCAPE;
        final int width = Math.min((int) (dm.widthPixels * (land ? 0.65f : 0.9f)),
                (int) (520 * dm.density));
        getWindow().setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private void build() {
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.gb_alert_dialog);

        final View topPanel = findViewById(R.id.gb_top_panel);
        final View contentPanel = findViewById(R.id.gb_content_panel);
        final FrameLayout customPanel = (FrameLayout) findViewById(R.id.gb_custom_panel);
        final FrameLayout custom = (FrameLayout) findViewById(R.id.gb_custom);
        final View buttonPanel = findViewById(R.id.gb_button_panel);

        // Title
        final boolean hasTitle = mTitle != null && mTitle.length() > 0;
        ((TextView) findViewById(R.id.gb_title)).setText(mTitle);
        if (mIcon != 0) {
            final ImageView icon = (ImageView) findViewById(R.id.gb_icon);
            icon.setImageResource(mIcon);
            icon.setVisibility(View.VISIBLE);
        }
        topPanel.setVisibility(hasTitle ? View.VISIBLE : View.GONE);

        // Message, or the list in its place (the list uses the bright content panel)
        if (mAdapter != null) {
            mListView = new ListView(getContext());
            mListView.setDivider(getContext().getResources().getDrawable(
                    R.drawable.gb_divider_horizontal_bright));
            mListView.setSelector(R.drawable.gb_list_selector_background);
            mListView.setCacheColorHint(0);
            if (mMode != MODE_PLAIN) {
                mListView.setChoiceMode(mMode == MODE_SINGLE ? ListView.CHOICE_MODE_SINGLE
                        : ListView.CHOICE_MODE_MULTIPLE);
            }
            mListView.setAdapter(mAdapter);
            if (mMode == MODE_SINGLE && mChecked > -1) {
                mListView.setItemChecked(mChecked, true);
                mListView.setSelection(mChecked);
            } else if (mMode == MODE_MULTI && mMultiChecked != null) {
                for (int i = 0; i < mMultiChecked.length; i++) {
                    mListView.setItemChecked(i, mMultiChecked[i]);
                }
            }
            mListView.setOnItemClickListener((parent, view, position, id) -> {
                if (mMode == MODE_MULTI) {
                    final boolean checked = mListView.isItemChecked(position);
                    if (mMultiChecked != null) mMultiChecked[position] = checked;
                    if (mMultiListener != null) {
                        mMultiListener.onClick(this, position, checked);
                    }
                } else {
                    if (mItemListener != null) mItemListener.onClick(this, position);
                    if (mMode == MODE_PLAIN) dismiss();
                }
            });
            final ViewGroup content = (ViewGroup) contentPanel;
            content.removeView(findViewById(R.id.gb_scroll));
            content.addView(mListView, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        } else if (mMessage != null) {
            ((TextView) findViewById(R.id.gb_message)).setText(mMessage);
        } else {
            contentPanel.setVisibility(View.GONE);
        }

        // Custom view
        if (mView != null) {
            custom.addView(mView, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        } else {
            customPanel.setVisibility(View.GONE);
        }

        // Buttons
        final int[] ids = { R.id.gb_button1, R.id.gb_button2, R.id.gb_button3 };
        final int[] which = { DialogInterface.BUTTON_POSITIVE, DialogInterface.BUTTON_NEGATIVE,
                DialogInterface.BUTTON_NEUTRAL };
        int count = 0;
        for (int i = 0; i < 3; i++) {
            final Button b = (Button) findViewById(ids[i]);
            if (mButtonText[i] == null) continue;
            count++;
            final int index = i;
            b.setText(mButtonText[i]);
            b.setVisibility(View.VISIBLE);
            b.setOnClickListener(v -> {
                if (mButtonListener[index] != null) {
                    mButtonListener[index].onClick(this, which[index]);
                }
                dismiss();
            });
        }
        final boolean hasButtons = count > 0;
        if (count == 1) { // a single button is centered
            findViewById(R.id.gb_left_spacer).setVisibility(View.VISIBLE);
            findViewById(R.id.gb_right_spacer).setVisibility(View.VISIBLE);
        }
        buttonPanel.setVisibility(hasButtons ? View.VISIBLE : View.GONE);

        setBackgrounds(topPanel, contentPanel, mView != null ? customPanel : null, buttonPanel,
                hasTitle, hasButtons);
    }

    /** AlertController.setBackground(): the two-tone popup frames. */
    private void setBackgrounds(View topPanel, View contentPanel, View customPanel,
            View buttonPanel, boolean hasTitle, boolean hasButtons) {
        final View[] views = new View[4];
        final boolean[] light = new boolean[4];
        int pos = 0;
        if (hasTitle) {
            views[pos] = topPanel;
            light[pos] = false;
            pos++;
        }
        views[pos] = contentPanel.getVisibility() == View.GONE ? null : contentPanel;
        light[pos] = mListView != null;
        pos++;
        if (customPanel != null) {
            views[pos] = customPanel;
            light[pos] = mInverse;
            pos++;
        }
        if (hasButtons) {
            views[pos] = buttonPanel;
            light[pos] = true;
        }

        View lastView = null;
        boolean lastLight = false;
        boolean setView = false;
        for (int i = 0; i < views.length; i++) {
            final View v = views[i];
            if (v == null) continue;
            if (lastView != null) {
                if (!setView) {
                    lastView.setBackgroundResource(lastLight ? R.drawable.gb_popup_top_bright
                            : R.drawable.gb_popup_top_dark);
                } else {
                    lastView.setBackgroundResource(lastLight ? R.drawable.gb_popup_center_bright
                            : R.drawable.gb_popup_center_dark);
                }
                setView = true;
            }
            lastView = v;
            lastLight = light[i];
        }
        if (lastView != null) {
            if (setView) {
                // lists use the bright frame, but the button row the medium one
                lastView.setBackgroundResource(lastLight
                        ? (hasButtons ? R.drawable.gb_popup_bottom_medium
                                : R.drawable.gb_popup_bottom_bright)
                        : R.drawable.gb_popup_bottom_dark);
            } else {
                lastView.setBackgroundResource(lastLight ? R.drawable.gb_popup_full_bright
                        : R.drawable.gb_popup_full_dark);
            }
        }
    }

    /** Same calls as android.app.AlertDialog.Builder, as far as the launcher uses them. */
    public static class Builder {
        private final Context mContext;
        private final GbDialog mDialog;

        public Builder(Context context) {
            mContext = context;
            mDialog = new GbDialog(context);
        }

        public Builder setIcon(int iconRes) {
            mDialog.mIcon = iconRes;
            return this;
        }

        public Builder setTitle(int titleRes) {
            mDialog.mTitle = mContext.getText(titleRes);
            return this;
        }

        public Builder setTitle(CharSequence title) {
            mDialog.mTitle = title;
            return this;
        }

        public Builder setMessage(int messageRes) {
            mDialog.mMessage = mContext.getText(messageRes);
            return this;
        }

        public Builder setMessage(CharSequence message) {
            mDialog.mMessage = message;
            return this;
        }

        public Builder setView(View view) {
            mDialog.mView = view;
            return this;
        }

        public Builder setCancelable(boolean cancelable) {
            mDialog.setCancelable(cancelable);
            return this;
        }

        public Builder setOnCancelListener(DialogInterface.OnCancelListener listener) {
            mDialog.setOnCancelListener(listener);
            return this;
        }

        /** Bright instead of dark frame for the custom view (AlertDialog's inverse background). */
        public Builder setInverseBackgroundForced(boolean inverse) {
            mDialog.mInverse = inverse;
            return this;
        }

        public Builder setPositiveButton(int textRes, DialogInterface.OnClickListener l) {
            return setButton(0, mContext.getText(textRes), l);
        }

        public Builder setPositiveButton(CharSequence text, DialogInterface.OnClickListener l) {
            return setButton(0, text, l);
        }

        public Builder setNegativeButton(int textRes, DialogInterface.OnClickListener l) {
            return setButton(1, mContext.getText(textRes), l);
        }

        public Builder setNegativeButton(CharSequence text, DialogInterface.OnClickListener l) {
            return setButton(1, text, l);
        }

        private Builder setButton(int index, CharSequence text,
                DialogInterface.OnClickListener l) {
            mDialog.mButtonText[index] = text;
            mDialog.mButtonListener[index] = l;
            return this;
        }

        /** List of items, the dialog closes itself after a click. */
        public Builder setAdapter(ListAdapter adapter, DialogInterface.OnClickListener l) {
            mDialog.mAdapter = adapter;
            mDialog.mItemListener = l;
            mDialog.mMode = MODE_PLAIN;
            return this;
        }

        /** The caller closes the dialog. */
        public Builder setSingleChoiceItems(CharSequence[] items, int checked,
                DialogInterface.OnClickListener l) {
            mDialog.mAdapter = new ArrayAdapter<CharSequence>(themed(mContext),
                    R.layout.gb_select_dialog_singlechoice, android.R.id.text1, items);
            mDialog.mItemListener = l;
            mDialog.mChecked = checked;
            mDialog.mMode = MODE_SINGLE;
            return this;
        }

        public Builder setMultiChoiceItems(CharSequence[] items, boolean[] checked,
                DialogInterface.OnMultiChoiceClickListener l) {
            mDialog.mAdapter = new ArrayAdapter<CharSequence>(themed(mContext),
                    R.layout.gb_select_dialog_multichoice, android.R.id.text1, items);
            mDialog.mMultiListener = l;
            mDialog.mMultiChecked = checked;
            mDialog.mMode = MODE_MULTI;
            return this;
        }

        public GbDialog create() {
            mDialog.build();
            return mDialog;
        }

        public GbDialog show() {
            final GbDialog dialog = create();
            dialog.show();
            return dialog;
        }
    }
}
