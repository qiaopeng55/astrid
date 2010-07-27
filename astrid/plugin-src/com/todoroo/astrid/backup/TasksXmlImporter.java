package com.todoroo.astrid.backup;

import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.StringTokenizer;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;

import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.res.Resources;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.google.ical.values.RRule;
import com.timsu.astrid.R;
import com.todoroo.andlib.data.TodorooCursor;
import com.todoroo.andlib.service.ContextManager;
import com.todoroo.andlib.service.ExceptionService;
import com.todoroo.andlib.sql.Criterion;
import com.todoroo.andlib.sql.Query;
import com.todoroo.andlib.utility.DateUtilities;
import com.todoroo.astrid.core.PluginServices;
import com.todoroo.astrid.legacy.LegacyImportance;
import com.todoroo.astrid.legacy.LegacyRepeatInfo;
import com.todoroo.astrid.legacy.LegacyRepeatInfo.LegacyRepeatInterval;
import com.todoroo.astrid.legacy.LegacyTaskModel;
import com.todoroo.astrid.model.Metadata;
import com.todoroo.astrid.model.Task;
import com.todoroo.astrid.rmilk.data.MilkTask;
import com.todoroo.astrid.service.MetadataService;
import com.todoroo.astrid.service.TaskService;
import com.todoroo.astrid.tags.TagService;

public class TasksXmlImporter {

    // --- public interface

    /**
     * Import tasks from the given file
     *
     * @param input
     * @param runAfterImport
     */
    public static void importTasks(Context context, String input, Runnable runAfterImport) {
        new TasksXmlImporter(context, input, runAfterImport);
    }

    // --- implementation

    private final Handler importHandler;
    private int taskCount;
    private int importCount;
    private int skipCount;
    private final String input;

    private final Context context;
    private final TaskService taskService = PluginServices.getTaskService();
    private final MetadataService metadataService = PluginServices.getMetadataService();
    private final ExceptionService exceptionService = PluginServices.getExceptionService();
    private final ProgressDialog progressDialog;
    private final Runnable runAfterImport;

    private void setProgressMessage(final String message) {
        importHandler.post(new Runnable() {
            public void run() {
                progressDialog.setMessage(message);
            }
        });
    }

    /**
     * Import tasks.
     * @param runAfterImport optional runnable after import
     */
    private TasksXmlImporter(final Context context, String input, Runnable runAfterImport) {
        this.input = input;
        this.context = context;
        this.runAfterImport = runAfterImport;
        progressDialog = new ProgressDialog(context);

        importHandler = new Handler();
        importHandler.post(new Runnable() {
            @Override
            public void run() {
                progressDialog.setIcon(android.R.drawable.ic_dialog_info);
                progressDialog.setTitle(R.string.import_progress_title);
                progressDialog.setProgressStyle(ProgressDialog.STYLE_SPINNER);
                progressDialog.setMessage(context.getString(R.string.import_progress_open));
                progressDialog.setCancelable(false);
                progressDialog.setIndeterminate(true);
                progressDialog.show();
            }
        });
        new Thread(new Runnable() {
            @Override
            public void run() {
                Looper.prepare();
                try {
                    performImport();
                } catch (IOException e) {
                    exceptionService.displayAndReportError(context,
                            context.getString(R.string.backup_TXI_error), e);
                } catch (XmlPullParserException e) {
                    exceptionService.displayAndReportError(context,
                            context.getString(R.string.backup_TXI_error), e);
                }
                Looper.loop();
            }
        }).start();
    }

    @SuppressWarnings("nls")
    private void performImport() throws IOException, XmlPullParserException {
        taskCount = 0;
        importCount = 0;
        skipCount = 0;
        XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
        XmlPullParser xpp = factory.newPullParser();
        xpp.setInput(new FileReader(input));

        try {
            while (xpp.next() != XmlPullParser.END_DOCUMENT) {
                String tag = xpp.getName();
                if (xpp.getEventType() == XmlPullParser.END_TAG) {
                    // Ignore end tags
                    continue;
                }
                if (tag != null) {
                    // Process <astrid ... >
                    if (tag.equals(BackupConstants.ASTRID_TAG)) {
                        String version = xpp.getAttributeValue(null, BackupConstants.ASTRID_ATTR_FORMAT);
                        int intVersion;
                        try {
                            intVersion = version == null ? 1 : Integer.parseInt(version);
                        } catch (Exception e) {
                            throw new UnsupportedOperationException(
                                    "Did not know how to import tasks with xml format '" +
                                    version + "'");
                        }
                        switch(intVersion) {
                        case 1:
                            new Astrid2TaskImporter(xpp);
                            break;
                        case 2:
                            new Astrid3TaskImporter(xpp);
                            break;
                        default:
                            throw new UnsupportedOperationException(
                                    "Did not know how to import tasks with xml format number '" +
                                    version + "'");
                        }
                    }
                }
            }
        } finally {
            progressDialog.dismiss();
            showSummary();
        }
    }

    private void showSummary() {
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(R.string.import_summary_title);
        Resources r = context.getResources();
        String message = context.getString(R.string.import_summary_message,
                input,
                r.getQuantityString(R.plurals.Ntasks, taskCount, taskCount),
                r.getQuantityString(R.plurals.Ntasks, importCount, importCount),
                r.getQuantityString(R.plurals.Ntasks, skipCount, skipCount));
        builder.setMessage(message);
        builder.setPositiveButton(context.getString(android.R.string.ok),
                new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int id) {
                        dialog.dismiss();
                        if (runAfterImport != null) {
                            importHandler.post(runAfterImport);
                        }
                    }
        });
        builder.show();
    }

    // --- importers

    private class Astrid3TaskImporter {
        @SuppressWarnings("unused")
        private final XmlPullParser xpp;

        public Astrid3TaskImporter(XmlPullParser xpp) {
            this.xpp = xpp;
            // TODO
        }
    }

    private class Astrid2TaskImporter {
        private final XmlPullParser xpp;
        private Task currentTask = null;
        private String upgradeNotes = null;
        private boolean syncOnComplete = false;

        private final ArrayList<String> tags = new ArrayList<String>();

        public Astrid2TaskImporter(XmlPullParser xpp) throws XmlPullParserException, IOException {
            this.xpp = xpp;

            while (xpp.next() != XmlPullParser.END_DOCUMENT) {
                String tag = xpp.getName();

                if(BackupConstants.TASK_TAG.equals(tag) && xpp.getEventType() == XmlPullParser.END_TAG)
                    saveTags();
                else if (tag == null || xpp.getEventType() == XmlPullParser.END_TAG)
                    continue;
                else if (tag.equals(BackupConstants.TASK_TAG)) {
                    // Parse <task ... >
                    currentTask = parseTask();
                } else if (currentTask != null) {
                    // These tags all require that we have a task to associate
                    // them with.
                    if (tag.equals(BackupConstants.TAG_TAG)) {
                        // Process <tag ... >
                        parseTag();
                    } else if (tag.equals(BackupConstants.ALERT_TAG)) {
                        // Process <alert ... >
                        parseAlert();
                    } else if (tag.equals(BackupConstants.SYNC_TAG)) {
                        // Process <sync ... >
                        parseSync();
                    }
                }
            }
        }

        private boolean parseSync() {
            String service = xpp.getAttributeValue(null, BackupConstants.SYNC_ATTR_SERVICE);
            String remoteId = xpp.getAttributeValue(null, BackupConstants.SYNC_ATTR_REMOTE_ID);
            if (service != null && remoteId != null) {
                StringTokenizer strtok = new StringTokenizer(remoteId, "|"); //$NON-NLS-1$
                String taskId = strtok.nextToken();
                String taskSeriesId = strtok.nextToken();
                String listId = strtok.nextToken();

                Metadata metadata = new Metadata();
                metadata.setValue(Metadata.TASK, currentTask.getId());
                metadata.setValue(MilkTask.LIST_ID, Long.parseLong(listId));
                metadata.setValue(MilkTask.TASK_SERIES_ID, Long.parseLong(taskSeriesId));
                metadata.setValue(MilkTask.TASK_ID, Long.parseLong(taskId));
                metadata.setValue(MilkTask.REPEATING, syncOnComplete ? 1 : 0);
                metadataService.save(metadata);
                return true;
            }
            return false;
        }

        private boolean parseAlert() {
            // drop it
            return false;
        }

        private boolean parseTag() {
            String tagName = xpp.getAttributeValue(null, BackupConstants.TAG_ATTR_NAME);
            tags.add(tagName);
            return true;
        }

        private void saveTags() {
            if(currentTask != null && tags.size() > 0) {
                TagService.getInstance().synchronizeTags(currentTask.getId(), tags);
            }
            tags.clear();
        }

        @SuppressWarnings("nls")
        private Task parseTask() {
            taskCount++;
            setProgressMessage(context.getString(R.string.import_progress_read,
                    taskCount));

            String taskName = xpp.getAttributeValue(null, LegacyTaskModel.NAME);
            Date creationDate = null;
            String createdString = xpp.getAttributeValue(null,
                    LegacyTaskModel.CREATION_DATE);
            if (createdString != null) {
                creationDate = BackupDateUtilities.getDateFromIso8601String(createdString);
            }

            // if we don't have task name or creation date, skip
            if (creationDate == null || taskName == null) {
                skipCount++;
                return null;
            }

            // if the task's name and creation date match an existing task, skip
            TodorooCursor<Task> cursor = taskService.query(Query.select(Task.ID).
                    where(Criterion.and(Task.TITLE.eq(taskName),
                            Task.CREATION_DATE.like(creationDate.getTime()/1000L + "%"))));
            try {
                if(cursor.getCount() > 0) {
                    skipCount++;
                    return null;
                }
            } finally {
                cursor.close();
            }

            // else, make a new task model and add away.
            Task task = new Task();
            int numAttributes = xpp.getAttributeCount();
            for (int i = 0; i < numAttributes; i++) {
                String fieldName = xpp.getAttributeName(i);
                String fieldValue = xpp.getAttributeValue(i);
                if(!setTaskField(task, fieldName, fieldValue)) {
                    Log.i("astrid-xml-import", "Task: " + taskName + ": Unknown field '" +
                            fieldName + "' with value '" + fieldValue + "' disregarded.");
                }
            }

            if(upgradeNotes != null) {
                if(task.containsValue(Task.NOTES) && task.getValue(Task.NOTES).length() > 0)
                    task.setValue(Task.NOTES, task.getValue(Task.NOTES) + "\n" + upgradeNotes);
                else
                    task.setValue(Task.NOTES, upgradeNotes);
                upgradeNotes = null;
            }

            // Save the task to the database.
            taskService.save(task, false);
            importCount++;
            return task;
        }

        /** helper method to set field on a task */
        @SuppressWarnings("nls")
        private final boolean setTaskField(Task task, String field, String value) {
            if(field.equals(LegacyTaskModel.ID)) {
                // ignore
            }
            else if(field.equals(LegacyTaskModel.NAME)) {
                task.setValue(Task.TITLE, value);
            }
            else if(field.equals(LegacyTaskModel.NOTES)) {
                task.setValue(Task.NOTES, value);
            }
            else if(field.equals(LegacyTaskModel.PROGRESS_PERCENTAGE)) {
                // ignore
            }
            else if(field.equals(LegacyTaskModel.IMPORTANCE)) {
                task.setValue(Task.IMPORTANCE, LegacyImportance.valueOf(value).ordinal());
            }
            else if(field.equals(LegacyTaskModel.ESTIMATED_SECONDS)) {
                task.setValue(Task.ESTIMATED_SECONDS, Integer.parseInt(value));
            }
            else if(field.equals(LegacyTaskModel.ELAPSED_SECONDS)) {
                task.setValue(Task.ELAPSED_SECONDS, Integer.parseInt(value));
            }
            else if(field.equals(LegacyTaskModel.TIMER_START)) {
                task.setValue(Task.TIMER_START,
                        BackupDateUtilities.getDateFromIso8601String(value).getTime());
            }
            else if(field.equals(LegacyTaskModel.DEFINITE_DUE_DATE)) {
                String preferred = xpp.getAttributeValue(null, LegacyTaskModel.PREFERRED_DUE_DATE);
                if(preferred != null) {
                    Date preferredDate = BackupDateUtilities.getDateFromIso8601String(value);
                    upgradeNotes = "Goal Deadline: " +
                            DateUtilities.getFormattedDate(ContextManager.getContext(),
                                    preferredDate);
                }
                task.setValue(Task.DUE_DATE,
                        BackupDateUtilities.getDateFromIso8601String(value).getTime());
            }
            else if(field.equals(LegacyTaskModel.PREFERRED_DUE_DATE)) {
                String definite = xpp.getAttributeValue(null, LegacyTaskModel.DEFINITE_DUE_DATE);
                if(definite != null)
                    ; // handled above
                else
                    task.setValue(Task.DUE_DATE,
                            BackupDateUtilities.getDateFromIso8601String(value).getTime());
            }
            else if(field.equals(LegacyTaskModel.HIDDEN_UNTIL)) {
                task.setValue(Task.HIDE_UNTIL,
                        BackupDateUtilities.getDateFromIso8601String(value).getTime());
            }
            else if(field.equals(LegacyTaskModel.BLOCKING_ON)) {
                // ignore
            }
            else if(field.equals(LegacyTaskModel.POSTPONE_COUNT)) {
                task.setValue(Task.POSTPONE_COUNT, Integer.parseInt(value));
            }
            else if(field.equals(LegacyTaskModel.NOTIFICATIONS)) {
                task.setValue(Task.REMINDER_PERIOD, Integer.parseInt(value) * 1000L);
            }
            else if(field.equals(LegacyTaskModel.CREATION_DATE)) {
                task.setValue(Task.CREATION_DATE,
                        BackupDateUtilities.getDateFromIso8601String(value).getTime());
            }
            else if(field.equals(LegacyTaskModel.COMPLETION_DATE)) {
                task.setValue(Task.COMPLETION_DATE,
                        BackupDateUtilities.getDateFromIso8601String(value).getTime());
            }
            else if(field.equals(LegacyTaskModel.NOTIFICATION_FLAGS)) {
                task.setValue(Task.REMINDER_FLAGS, Integer.parseInt(value));
            }
            else if(field.equals(LegacyTaskModel.LAST_NOTIFIED)) {
                task.setValue(Task.REMINDER_LAST,
                        BackupDateUtilities.getDateFromIso8601String(value).getTime());
            }
            else if(field.equals("repeat_interval")) {
                // handled below
            }
            else if(field.equals("repeat_value")) {
                int repeatValue = Integer.parseInt(value);
                String repeatInterval = xpp.getAttributeValue(null, "repeat_interval");
                if(repeatValue > 0 && repeatInterval != null) {
                    LegacyRepeatInterval interval = LegacyRepeatInterval.valueOf(repeatInterval);
                    LegacyRepeatInfo repeatInfo = new LegacyRepeatInfo(interval, repeatValue);
                    RRule rrule = repeatInfo.toRRule();
                    task.setValue(Task.RECURRENCE, rrule.toIcal());
                }
            }
            else if(field.equals(LegacyTaskModel.FLAGS)) {
                if(Integer.parseInt(value) == LegacyTaskModel.FLAG_SYNC_ON_COMPLETE)
                    syncOnComplete = true;
            }
            else {
                return false;
            }

            return true;
        }
    }

}