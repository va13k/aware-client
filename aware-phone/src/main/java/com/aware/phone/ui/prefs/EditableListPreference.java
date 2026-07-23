package com.aware.phone.ui.prefs;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.res.TypedArray;
import android.preference.ListPreference;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.AttributeSet;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.aware.phone.R;

/**
 * A {@link ListPreference} that offers the curated preset values AND a "Custom…" entry
 * which opens a numeric-input dialog, so a value can either be picked from the recommended
 * options or typed by hand.
 *
 * <p>The optional unit shown next to a custom value (e.g. "seconds") comes from the custom
 * {@code customUnit} XML attribute. It deliberately does not use {@code android:dialogMessage}:
 * legacy preference dialogs render a message instead of their choice list, which would hide every
 * preset and the Custom row.</p>
 */
public class EditableListPreference extends ListPreference {

    private static final String CUSTOM_LABEL = "Custom value…";
    private static final int FORMAT_STORED_VALUE = 0;
    private static final int FORMAT_SAMPLING_RATE_HZ = 1;
    private final String customUnit;
    private final int customValueFormat;

    public EditableListPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        TypedArray values = context.obtainStyledAttributes(
                attrs, R.styleable.EditableListPreference);
        customUnit = values.getString(R.styleable.EditableListPreference_customUnit);
        customValueFormat = values.getInt(
                R.styleable.EditableListPreference_customValueFormat, FORMAT_STORED_VALUE);
        values.recycle();
    }

    private CharSequence unit() {
        return customUnit;
    }

    @Override
    protected void onPrepareDialogBuilder(AlertDialog.Builder builder) {
        final CharSequence[] entries = getEntries();
        final CharSequence[] entryValues = getEntryValues();

        // Append a trailing "Custom…" row to the recommended presets.
        final CharSequence[] items = new CharSequence[entries.length + 1];
        System.arraycopy(entries, 0, items, 0, entries.length);
        items[entries.length] = CUSTOM_LABEL;

        int checked = findIndexOfValue(getValue());
        if (checked < 0) checked = entries.length; // current value is a custom one

        final int customIndex = entries.length;
        builder.setSingleChoiceItems(items, checked, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                dialog.dismiss();
                if (which == customIndex) {
                    showCustomDialog();
                } else {
                    String value = entryValues[which].toString();
                    if (callChangeListener(value)) setValue(value);
                }
            }
        });

        // Tapping a row commits immediately, so no OK button is needed (Cancel stays).
        builder.setPositiveButton(null, null);
    }

    private void showCustomDialog() {
        final EditText input = new EditText(getContext());
        input.setInputType(customValueFormat == FORMAT_SAMPLING_RATE_HZ
                ? InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL
                : InputType.TYPE_CLASS_NUMBER);
        if (!TextUtils.isEmpty(unit())) input.setHint(unit());
        String current = displayValue(getValue());
        if (!TextUtils.isEmpty(current)) {
            input.setText(current);
            input.setSelection(current.length());
        }

        AlertDialog.Builder customDialog = new AlertDialog.Builder(getContext())
                .setTitle(getDialogTitle() != null ? getDialogTitle() : getTitle());
        if (customValueFormat == FORMAT_SAMPLING_RATE_HZ) {
            customDialog.setView(samplingRateInput(input));
        } else {
            customDialog.setView(input);
        }

        customDialog
                .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String enteredValue = input.getText().toString().trim();
                        String storedValue = storedValue(enteredValue);
                        if (!TextUtils.isEmpty(storedValue) && callChangeListener(storedValue)) {
                            setValue(storedValue);
                        }
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private LinearLayout samplingRateInput(final EditText input) {
        LinearLayout content = new LinearLayout(getContext());
        content.setOrientation(LinearLayout.VERTICAL);
        int horizontalPadding = (int) (24 * getContext().getResources()
                .getDisplayMetrics().density);
        content.setPadding(horizontalPadding, 0, horizontalPadding, 0);

        TextView explanation = new TextView(getContext());
        explanation.setText("Hz means samples per second.");
        content.addView(explanation);
        content.addView(input);

        final TextView conversion = new TextView(getContext());
        content.addView(conversion);
        updateSamplingRateConversion(conversion, input.getText().toString());
        input.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence value, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence value, int start, int before, int count) {
                updateSamplingRateConversion(conversion, value.toString());
            }

            @Override
            public void afterTextChanged(Editable value) {
            }
        });
        return content;
    }

    private void updateSamplingRateConversion(TextView conversion, String samplingRateHz) {
        if (TextUtils.isEmpty(samplingRateHz)) {
            conversion.setText("Example: 20 Hz = one sample every 50 ms (1/20 second)");
            return;
        }
        try {
            conversion.setText(FrequencyValueConverter
                    .samplingRateHzDescription(samplingRateHz));
        } catch (IllegalArgumentException ignored) {
            conversion.setText("Enter a sampling rate greater than 0 Hz");
        }
    }

    /**
     * The readable label for the current value: the matching preset entry, or a
     * "Custom: <value> <unit>" label when the value isn't one of the presets. Feeds both the
     * {@code %s} summary and the summary refresh done in Aware_Client.
     */
    @Override
    public CharSequence getEntry() {
        CharSequence preset = super.getEntry();
        if (preset != null) return preset;
        String value = getValue();
        if (TextUtils.isEmpty(value)) return null;
        value = displayValue(value);
        if (TextUtils.isEmpty(value)) return null;
        if (customValueFormat == FORMAT_SAMPLING_RATE_HZ) {
            return "Custom: " + value + " " + unit() + " ("
                    + FrequencyValueConverter.samplingRateHzInterval(value) + ")";
        }
        return TextUtils.isEmpty(unit())
                ? "Custom: " + value
                : "Custom: " + value + " " + unit();
    }

    private String storedValue(String displayedValue) {
        if (TextUtils.isEmpty(displayedValue)) return null;
        if (customValueFormat != FORMAT_SAMPLING_RATE_HZ) return displayedValue;
        try {
            return FrequencyValueConverter.samplingRateHzToPeriodUs(displayedValue);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private String displayValue(String storedValue) {
        if (TextUtils.isEmpty(storedValue)
                || customValueFormat != FORMAT_SAMPLING_RATE_HZ) {
            return storedValue;
        }
        try {
            return FrequencyValueConverter.periodUsToSamplingRateHz(storedValue);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
