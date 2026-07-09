package com.aware.phone.ui.prefs;

import android.content.Context;
import android.database.Cursor;
import android.preference.Preference;
import android.text.Html;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.aware.Aware;
import com.aware.phone.R;
import com.aware.phone.utils.AwareUtil;
import com.aware.providers.Aware_Provider;

import android.util.Patterns;

import java.text.DateFormat;
import java.util.Date;
import java.util.regex.Matcher;

public class StudyInfoPref extends Preference {

    public StudyInfoPref(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    public StudyInfoPref(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public StudyInfoPref(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public StudyInfoPref(Context context) {
        super(context);
    }

    @Override
    protected View onCreateView(ViewGroup parent) {
        super.onCreateView(parent);
        LayoutInflater inflater = (LayoutInflater) getContext().getSystemService(
                Context.LAYOUT_INFLATER_SERVICE);
        View view = inflater.inflate(R.layout.study_card, parent, false);

        TextView tvStudyName = view.findViewById(R.id.study_name);
        TextView tvStudyDesc = view.findViewById(R.id.study_description);
        TextView tvStudyContact = view.findViewById(R.id.study_contact);
        final TextView tvStudyEmail = view.findViewById(R.id.study_email);
        final TextView tvStudyLink = view.findViewById(R.id.study_link);
        TextView tvStudyJoined = view.findViewById(R.id.study_joined);
        TextView tvStudyStatus = view.findViewById(R.id.study_status);

        // Use the currently-enrolled study, independent of the WEBSERVICE_SERVER setting
        // (which can drift from the study URL after a reset). See Aware.getActiveStudy().
        Cursor study = Aware.getActiveStudy(getContext());
        if (study != null && study.moveToFirst()) {
            tvStudyName.setText(study.getString(
                    study.getColumnIndex(Aware_Provider.Aware_Studies.STUDY_TITLE)));
            tvStudyDesc.setText(Html.fromHtml(study.getString(study.getColumnIndex(
                    Aware_Provider.Aware_Studies.STUDY_DESCRIPTION)), null, null));

            // STUDY_PI is stored as "First Last\nContact: email@x.com". Show the researcher
            // name under Contact, and the email in its own tappable (blue) Email row.
            final String contact = study.getString(
                    study.getColumnIndex(Aware_Provider.Aware_Studies.STUDY_PI));
            setRow(view, R.id.row_contact, tvStudyContact, extractName(contact));

            final String email = extractEmail(contact);
            setRow(view, R.id.row_email, tvStudyEmail, email);
            if (email != null) {
                tvStudyEmail.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        AwareUtil.copyToClipboard(getContext(), "AWARE study contact", email);
                    }
                });
            }

            final String url = study.getString(
                    study.getColumnIndex(Aware_Provider.Aware_Studies.STUDY_URL));
            setRow(view, R.id.row_link, tvStudyLink, url);
            if (url != null && url.length() > 0) {
                tvStudyLink.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        AwareUtil.copyToClipboard(getContext(), "AWARE study link", url);
                    }
                });
            }

            long joined = (long) study.getDouble(
                    study.getColumnIndex(Aware_Provider.Aware_Studies.STUDY_JOINED));
            setRow(view, R.id.row_joined, tvStudyJoined,
                    joined > 0 ? DateFormat.getDateInstance().format(new Date(joined)) : null);

            // getActiveStudy() only returns enrolled studies.
            setRow(view, R.id.row_status, tvStudyStatus, "Enrolled");
        }
        if (study != null && !study.isClosed()) study.close();

        return view;
    }

    /**
     * Extracts the researcher name from a study contact/PI string (stored as
     * "First Last\nContact: email@x.com"): the first line with any "Contact:" label and
     * email removed. Returns null if nothing meaningful remains.
     */
    private static String extractName(String contact) {
        if (contact == null) return null;
        String name = contact;
        int newline = name.indexOf('\n');
        if (newline >= 0) name = name.substring(0, newline);
        name = name.replace("Contact:", "");
        String email = extractEmail(name);
        if (email != null) name = name.replace(email, "");
        name = name.trim();
        return name.length() > 0 ? name : null;
    }

    /**
     * Extracts the email address from a study contact/PI string (stored as
     * "First Last\nContact: email@x.com"), so tapping Email copies only the email.
     * Returns null if there is no email to copy.
     */
    private static String extractEmail(String contact) {
        if (contact == null) return null;
        Matcher matcher = Patterns.EMAIL_ADDRESS.matcher(contact);
        return matcher.find() ? matcher.group() : null;
    }

    /**
     * Sets a labeled row's value, hiding the whole row when the value is empty so the card
     * only shows populated fields.
     */
    private static void setRow(View card, int rowId, TextView valueView, String value) {
        View row = card.findViewById(rowId);
        if (value == null || value.trim().length() == 0) {
            if (row != null) row.setVisibility(View.GONE);
        } else {
            if (row != null) row.setVisibility(View.VISIBLE);
            valueView.setText(value);
        }
    }
}
