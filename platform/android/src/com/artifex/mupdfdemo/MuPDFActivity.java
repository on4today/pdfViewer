package com.artifex.mupdfdemo;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.Executor;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.DialogInterface.OnCancelListener;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.os.AsyncTask;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.RelativeLayout;
import android.widget.TextView;

import com.on4today.authentication.util.Logging;

class ThreadPerTaskExecutor implements Executor {
    public void execute(Runnable r) {
        new Thread(r).start();
    }
}

public class MuPDFActivity extends Activity {
    /* The core rendering instance */
    enum AcceptMode {Highlight, Underline, StrikeOut, Ink, CopyText};

    private MuPDFCore mCore;
    private MuPDFReaderView mDocView;
    private AlertDialog.Builder mAlertBuilder;
    private boolean mAlertsActive= false;
    private AsyncTask<Void,Void,MuPDFAlert> mAlertTask;
    private AlertDialog mAlertDialog;
    private int mZoomLevel = 1;
    private String mPdfMD5 = "";

    private ImageView mExitAppButton;
    private ImageView mZoomInButton;
    private ImageView mZoomOutButton;
    private ImageView mGoUpButton;
    private ImageView mGoDownButton;

    private TextView mExitAppText;
    private TextView mZoomInText;
    private TextView mZoomOutText;
    private TextView mGoUpText;
    private TextView mGoDownText;
    private TextView mPageNumberTextView;

    private ProgressBar mProgressBar;
    private TextView mProgressPercentText;
    private static final Logging sLogging = new Logging(MuPDFActivity.class);

    // Used to disable navigation, zooming buttons
    private boolean mFileLoaded = false;
    private boolean mStop = false;
    private FileReceiver mFileReceiver = null;


    public void createAlertWaiter() {
        mAlertsActive = true;
        // All mupdf library calls are performed on asynchronous tasks to avoid stalling
        // the UI. Some calls can lead to javascript-invoked requests to display an
        // alert dialog and collect a reply from the user. The task has to be blocked
        // until the user's reply is received. This method creates an asynchronous task,
        // the purpose of which is to wait of these requests and produce the dialog
        // in response, while leaving the core blocked. When the dialog receives the
        // user's response, it is sent to the core via replyToAlert, unblocking it.
        // Another alert-waiting task is then created to pick up the next alert.
        if (mAlertTask != null) {
            mAlertTask.cancel(true);
            mAlertTask = null;
        }
        if (mAlertDialog != null) {
            mAlertDialog.cancel();
            mAlertDialog = null;
        }
        mAlertTask = new AsyncTask<Void,Void,MuPDFAlert>() {

            @Override
            protected MuPDFAlert doInBackground(Void... arg0) {
                if (!mAlertsActive)
                    return null;

                return mCore.waitForAlert();
            }

            @SuppressWarnings("deprecation")
            @Override
            protected void onPostExecute(final MuPDFAlert result) {
                // core.waitForAlert may return null when shutting down
                if (result == null)
                    return;
                final MuPDFAlert.ButtonPressed pressed[] = new MuPDFAlert.ButtonPressed[3];
                for(int i = 0; i < 3; i++) {
                    pressed[i] = MuPDFAlert.ButtonPressed.None;
                }
                DialogInterface.OnClickListener listener = new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        mAlertDialog = null;
                        if (mAlertsActive) {
                            int index = 0;
                            switch (which) {
                            case AlertDialog.BUTTON1: index=0; break;
                            case AlertDialog.BUTTON2: index=1; break;
                            case AlertDialog.BUTTON3: index=2; break;
                            }
                            result.buttonPressed = pressed[index];
                            // Send the user's response to the core, so that it can
                            // continue processing.
                            mCore.replyToAlert(result);
                            // Create another alert-waiter to pick up the next alert.
                            createAlertWaiter();
                        }
                    }
                };
                mAlertDialog = mAlertBuilder.create();
                mAlertDialog.setTitle(result.title);
                mAlertDialog.setMessage(result.message);
                switch (result.iconType)
                {
                case Error:
                    break;
                case Warning:
                    break;
                case Question:
                    break;
                case Status:
                    break;
                }
                switch (result.buttonGroupType)
                {
                case OkCancel:
                    mAlertDialog.setButton(AlertDialog.BUTTON2, getString(R.string.cancel), listener);
                    pressed[1] = MuPDFAlert.ButtonPressed.Cancel;
                case Ok:
                    mAlertDialog.setButton(AlertDialog.BUTTON1, getString(R.string.okay), listener);
                    pressed[0] = MuPDFAlert.ButtonPressed.Ok;
                    break;
                case YesNoCancel:
                    mAlertDialog.setButton(AlertDialog.BUTTON3, getString(R.string.cancel), listener);
                    pressed[2] = MuPDFAlert.ButtonPressed.Cancel;
                case YesNo:
                    mAlertDialog.setButton(AlertDialog.BUTTON1, getString(R.string.yes), listener);
                    pressed[0] = MuPDFAlert.ButtonPressed.Yes;
                    mAlertDialog.setButton(AlertDialog.BUTTON2, getString(R.string.no), listener);
                    pressed[1] = MuPDFAlert.ButtonPressed.No;
                    break;
                }
                mAlertDialog.setOnCancelListener(new DialogInterface.OnCancelListener() {
                    public void onCancel(DialogInterface dialog) {
                        mAlertDialog = null;
                        if (mAlertsActive) {
                            result.buttonPressed = MuPDFAlert.ButtonPressed.None;
                            mCore.replyToAlert(result);
                            createAlertWaiter();
                        }
                    }
                });

                mAlertDialog.show();
            }
        };
        mAlertTask.executeOnExecutor(new ThreadPerTaskExecutor());
    }

    public void destroyAlertWaiter() {
        mAlertsActive = false;
        if (mAlertDialog != null) {
            mAlertDialog.cancel();
            mAlertDialog = null;
        }
        if (mAlertTask != null) {
            mAlertTask.cancel(true);
            mAlertTask = null;
        }
    }

    private MuPDFCore openBuffer(byte buffer[], String magic) {
        try {
            mCore = new MuPDFCore(this, buffer, magic);
            // New file: drop the old outline data
        }
        catch (Exception e) {
            e.printStackTrace();
            sLogging.error(e);
            return null;
        }
        return mCore;
    }

    private void saveLastOpenedPage(){
        if (mDocView != null && mPdfMD5 != null){
            // Store current page in the prefs against the file name,
            // so that we can pick it up each time the file is loaded
            // Other info is needed only for screen-orientation change,
            // so it can go in the bundle
            SharedPreferences prefs = getPreferences(Context.MODE_PRIVATE);
            SharedPreferences.Editor edit = prefs.edit();
            edit.putInt("page" + mPdfMD5, mDocView.getDisplayedViewIndex());
            edit.putInt("zoom" + mPdfMD5, mZoomLevel);
            edit.commit();
        }
    }

    private void setZoomLevel(int zoomLevel){
        if (zoomLevel < 1 || zoomLevel > 4){
            return;
        }

        mZoomLevel = zoomLevel;

        runOnUiThread(new Runnable(){

            @Override
            public void run() {
                switch (mZoomLevel){ // zoom level can go from 1-4: 1=fit height to screen, 2=fit width to screen, 3+4 = zoom in more
                case 1:
                    mZoomOutButton.setImageResource(R.drawable.zoom_out_disabled);
                    mZoomInButton.setImageResource(R.drawable.zoom_in);
                    break;
                case 2:
                    mZoomOutButton.setImageResource(R.drawable.zoom_out);
                    mZoomInButton.setImageResource(R.drawable.zoom_in);
                    break;
                case 3:
                    mZoomOutButton.setImageResource(R.drawable.zoom_out);
                    mZoomInButton.setImageResource(R.drawable.zoom_in);
                    break;
                case 4:
                    mZoomOutButton.setImageResource(R.drawable.zoom_out);
                    mZoomInButton.setImageResource(R.drawable.zoom_in_disabled);
                    break;
                }
                mDocView.zoomToLevel(mZoomLevel);
            }
        });
    }

    /**
     * Initializes the buttons (zoom/next page etc), and loads the PDF.
     */
    private void initPDF() {    	 
        // Navigation, zooming buttons
        mExitAppButton = (ImageView) findViewById(R.id.exitAppButton);
        mZoomInButton = (ImageView) findViewById(R.id.zoomInButton);
        mZoomOutButton = (ImageView) findViewById(R.id.zoomOutButton);
        mGoUpButton = (ImageView) findViewById(R.id.goUpButton);
        mGoDownButton = (ImageView) findViewById(R.id.goDownButton);

        // Navigation, zooming text labels
        mExitAppText = (TextView) findViewById(R.id.exitAppText);
        mZoomInText = (TextView) findViewById(R.id.zoomInText);
        mZoomOutText = (TextView) findViewById(R.id.zoomOutText);
        mGoUpText= (TextView) findViewById(R.id.goUpText);
        mGoDownText = (TextView) findViewById(R.id.goDownText);
        mPageNumberTextView = (TextView) findViewById(R.id.pageNumberText);

        // Handle navigation / zooming button events
        OnClickListener buttonListener = new OnClickListener() {

            public void onClick(View view) {
                // close the pdfreader
                if (view.getId() == R.id.exitAppButton){
                    mStop = true;
                    Intent stopIntent = new Intent("com.on4today.chromium.On4TodayBridge.A_INTENT");
                    
                    // stop loading the pdf file from the bridge
                    sendBroadcast(stopIntent.putExtra("stop_loading", true));

                    // stop the broadcasting
                    // check if it's already unregistered
                    if (mFileReceiver != null) {
                        try {
                            // stop the broadcasting
                            unregisterReceiver(mFileReceiver);
                        } catch (IllegalArgumentException ex) {
                            ex.printStackTrace();
                            sLogging.error(ex);
                        }
                        mFileReceiver = null;
                    }
                    finish();
                }

                // disable navigation, zoom buttons when loading the file
                if (mFileLoaded) {
                    if (view.getId() == R.id.zoomInButton || view.getId() == R.id.zoomInText){ // zoom in
                        setZoomLevel(mZoomLevel + 1);
                    } else if (view.getId() == R.id.zoomOutButton || view.getId() == R.id.zoomOutText){ // zoom out
                        setZoomLevel(mZoomLevel - 1);
                    } else if (view.getId() == R.id.goUpButton || view.getId() == R.id.goUpText){ // go to previous page
                        mDocView.moveToPrevious();	
                    } else if (view.getId() == R.id.goDownButton || view.getId() == R.id.goDownText){ // go to next page
                        mDocView.moveToNext(); 
                    }
                }
            }
        };

        // set click events button
        mExitAppButton.setOnClickListener(buttonListener);
        mZoomInButton.setOnClickListener(buttonListener);
        mZoomOutButton.setOnClickListener(buttonListener);
        mGoUpButton.setOnClickListener(buttonListener);
        mGoDownButton.setOnClickListener(buttonListener);
        mExitAppText.setOnClickListener(buttonListener);
        mZoomInText.setOnClickListener(buttonListener);
        mZoomOutText.setOnClickListener(buttonListener);
        mGoUpText.setOnClickListener(buttonListener);
        mGoDownText.setOnClickListener(buttonListener);

        // loading bar
        mProgressBar = (ProgressBar) findViewById(R.id.progressBar);
        mProgressPercentText = (TextView) findViewById(R.id.progressPercentText);
    }

    private void loadPDF(byte[] buffer) {		
        mCore = openBuffer(buffer, null);
        mPdfMD5 = calculateMd5(buffer, null);

        // show the progress bar
        RelativeLayout layout = (RelativeLayout) findViewById(R.id.background);
        layout.setBackgroundResource(R.drawable.background_green);
        View progressLayout = findViewById(R.id.progressLayout);
        progressLayout.setVisibility(View.GONE);

        // if something went wrong, display error dialog and then quit
        if (mCore == null) {
            AlertDialog alert = mAlertBuilder.create();
            alert.setTitle(R.string.cannot_open_document);
            alert.setButton(AlertDialog.BUTTON_POSITIVE, getString(R.string.dismiss),
                    new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int which) {
                    finish();
                }
            });
            alert.setOnCancelListener(new OnCancelListener() {
                @Override
                public void onCancel(DialogInterface dialog) {
                    finish();
                }
            });
            alert.show();
            return;
        }

        // Now create the UI.
        // First create the document view
        mDocView = new MuPDFReaderView(this) {

            @Override
            protected void onMoveToChild(int i) {
                if (mCore == null)
                    return;

                mPageNumberTextView.setText(String.format(getResources().getString(R.string.pageNumber), i + 1, mCore.countPages()));
                if (i == 0){
                    mGoDownButton.setImageResource(R.drawable.next_page);
                    mGoUpButton.setImageResource(R.drawable.previous_page_disabled);
                } else if (i == mCore.countPages() - 1) {
                    mGoUpButton.setImageResource(R.drawable.previous_page);
                    mGoDownButton.setImageResource(R.drawable.next_page_disabled);
                } else {
                    mGoUpButton.setImageResource(R.drawable.previous_page);
                    mGoDownButton.setImageResource(R.drawable.next_page);
                }		
                super.onMoveToChild(i);
            }

            @Override
            protected void onTapMainDocArea() {
            }

            @Override
            protected void onDocMotion() {
            }

            @Override
            protected void onHit(Hit item) {

            }
        };
        mDocView.setAdapter(new MuPDFPageAdapter(this, mCore));

        // Reinstate last state if it was recorded
        SharedPreferences prefs = getPreferences(Context.MODE_PRIVATE);
        int pageNum = prefs.getInt("page" + mPdfMD5, 0);
        mDocView.setDisplayedViewIndex(pageNum);

        final int zoomLevel = prefs.getInt("zoom" + mPdfMD5, 1);
        // the children must load first before the zoom level can be set
        mDocView.addPostSettleTask(new Runnable(){
            @Override
            public void run() {
                setZoomLevel(zoomLevel);
            }
        });

        // display PDF page left of menu bar
        RelativeLayout.LayoutParams lay = new RelativeLayout.LayoutParams(RelativeLayout.LayoutParams.MATCH_PARENT, RelativeLayout.LayoutParams.MATCH_PARENT);
        lay.addRule(RelativeLayout.LEFT_OF, R.id.exitAppButton);

        layout.addView(mDocView, lay);
        mPageNumberTextView.setText(String.format(getResources().getString(R.string.pageNumber), pageNum + 1, mCore.countPages())); // show current page

        // now that the file is loaded, activate navigation, zooming buttons
        mFileLoaded = true;
    }

    // FileReceiver class is used to receive local pdf between the bridge and mupdf
    private class FileReceiver extends BroadcastReceiver {
        private MuPDFActivity mMupdf;
        private ByteArrayOutputStream mFos;
        private Intent mBridgeIntent;

        // instantiate the broadcaster
        public FileReceiver(MuPDFActivity mupdf) {
            mBridgeIntent = new Intent("com.on4today.chromium.On4TodayBridge.A_INTENT");

            mMupdf = mupdf;
            mFos = new ByteArrayOutputStream();

            // Let the bridge know that the receiver is ready to receive the pdf bytes
            mMupdf.sendBroadcast(mBridgeIntent.putExtra("ready_to_load", true));
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            // load a section of the pdf bytes
            if (intent.getStringExtra("loading") != null) {
                // update the progress bar
                mMupdf.getProgressBar().setProgress(Integer.parseInt(intent.getStringExtra("loading")));
                mMupdf.getProgressPercentText().setText(intent.getStringExtra("loading") + " %");

                // write the bytes to the buffer
                mFos.write(intent.getByteArrayExtra("buffer"), 0, intent.getIntExtra("len", 0));
            }

            // when the all the bytes are sent open the file
            if (intent.getBooleanExtra("ready_to_open", false)) {
                byte buffer[] = mFos.toByteArray();

                // make sure to free the buffer
                try {
                    mFos.flush();
                    mFos.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }

                // load the PDF
                loadPDF(buffer);
            }
        }
    }

    // Open external pdf
    AsyncTask<Void, Void, Void> loadExternalPDF = new AsyncTask<Void, Void, Void>(){
        private String mDemoUrl = "http://www.act.org/compass/sample/pdf/reading.pdf";
        private byte[] mData;

        @Override
        protected Void doInBackground(Void... params) {
            try {
                ByteArrayOutputStream fos = new ByteArrayOutputStream();
                URL url = new URL(mDemoUrl);
                URLConnection connection = url.openConnection();
                int size = connection.getContentLength(); // get size of document

                InputStream in = connection.getInputStream();

                byte[] buf = new byte[4096];
                double sumCount = 0.0;
                while (true) { // actual downloading
                    int len = in.read(buf);
                    if (size > 0) {
                        sumCount += len;
                        final int percentage = (int) (sumCount / size * 100);
                        runOnUiThread(new Runnable(){ // update progress bar
                            public void run() {
                                mProgressBar.setProgress(percentage);
                                mProgressPercentText.setText(percentage + " %");
                            }
                        });
                    }

                    if (len == -1) {
                        break;
                    }
                    fos.write(buf, 0, len);
                }
                mData = fos.toByteArray();
                in.close();
                fos.flush();
                fos.close();
            } catch (Exception e) {
                e.printStackTrace();
                finish();
            }
            return null;
        }

        @Override
        protected void onPostExecute(Void result) {
            // load the PDF
            loadPDF(mData);
        }
    };

    public ProgressBar getProgressBar() {
        return mProgressBar;
    }

    public void setProgressBar(ProgressBar pProgressBarp) {
        mProgressBar = pProgressBarp;
    }

    public TextView getProgressPercentText() {
        return mProgressPercentText;
    }

    public void setProgressPercentText(TextView pProgressPercentTextp) {
        mProgressPercentText = pProgressPercentTextp;
    }

    /** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mAlertBuilder = new AlertDialog.Builder(this);

        Intent intent = getIntent();
        String language = (intent.getStringExtra("language") == null) ? "english" : intent.getStringExtra("language"); // check if language is selected via intent; otherwise, use "english"

        if (!language.equals("english")){ // switch to another language
            Resources res = getResources();
            // Change locale settings in the app.
            DisplayMetrics dm = res.getDisplayMetrics();
            android.content.res.Configuration conf = res.getConfiguration();
            String languageCode = "en";
            if (language.equals("spanish"))
                languageCode = "es";

            conf.locale = new Locale(languageCode);
            res.updateConfiguration(conf, dm);
        }

        // Stick the document view and the buttons overlay into a parent view
        setContentView(R.layout.mainactivity);

        // instantiate pdfreader GUI
        initPDF();

        // run the broadcaster and load the PDF locally
        if (intent.getBooleanExtra("open_local_pdf", false)) {
            // create a new receiver
            mFileReceiver = new FileReceiver(this);
            // register the broadcast receiver, so it can be called from the bridge
            registerReceiver(mFileReceiver, new IntentFilter("com.artifex.mupdfdemo.MuPDFActivity.A_CUSTOM_INTENT"));
        } else {
            // download and open demo PDF
            loadExternalPDF.execute();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        saveLastOpenedPage();
    }

    @Override
    protected void onPause() {
        // In case the onDestroy is not called
        // check if it's already unregistered
        if (mFileReceiver != null) {
            try {
                // stop the broadcasting
                this.unregisterReceiver(mFileReceiver);
            } catch (IllegalArgumentException ex) {
                ex.printStackTrace();
                sLogging.error(ex);
            }
            mFileReceiver = null;
        }
        super.onPause();
        saveLastOpenedPage();
    }

    public void onDestroy() {
        // check if it's already unregistered
        if (mFileReceiver != null) {
            try {
                // stop the broadcasting
                this.unregisterReceiver(mFileReceiver);
            } catch (IllegalArgumentException ex) {
                ex.printStackTrace();
                sLogging.error(ex);
            }
            mFileReceiver = null;
        }
        if (mDocView != null) {
            saveLastOpenedPage();

            mDocView.applyToChildren(new ReaderView.ViewMapper() {
                void applyToView(View view) {
                    ((MuPDFView)view).releaseBitmaps();
                }
            });
        }
        if (mCore != null)
            mCore.onDestroy();
        if (mAlertTask != null) {
            mAlertTask.cancel(true);
            mAlertTask = null;
        }
        mCore = null;
        super.onDestroy();
    }

    /**
     * Calculates MD5 of the given file
     * @param file
     * @return MD5 of empty String
     */
    @SuppressWarnings("unused")
    private String calculateMd5(File file) {
        return calculateMd5(file, (byte[][]) null);
    }

    /**
     * Calculates MD5 of the given file
     * @param file
     * @param concatenatedWith
     * @return MD5 of empty String
     */
    private String calculateMd5(File file, byte[]... concatenatedWith) {
        InputStream inputStream = null;
        try {
            inputStream = new FileInputStream(file);
            byte[] buffer = new byte[1024];
            MessageDigest digest = MessageDigest.getInstance("MD5");
            int numRead = 0;
            while (numRead != -1) {
                numRead = inputStream.read(buffer);
                if (numRead > 0)
                    digest.update(buffer, 0, numRead);
            }

            if (concatenatedWith != null){
                for (byte[] byteArray : concatenatedWith){
                    digest.update(byteArray);
                }
            }

            byte [] md5Bytes = digest.digest();
            String md5hash = "";
            for (int i = 0; i < md5Bytes.length; i++) {
                md5hash += Integer.toString(( md5Bytes[i] & 0xff ) + 0x100, 16).substring(1);
            }

            return md5hash;
        } catch (Exception e) {
            e.printStackTrace();
            sLogging.error(e);
            return "";
        } finally {
            if (inputStream != null) {
                try {
                    inputStream.close();
                } catch (Exception e) { }
            }
        }
    }

    /**
     * Calculates MD5 of the given buffer
     * @param buffer
     * @return MD5 of empty String
     */
    @SuppressWarnings("unused")
    private String calculateMd5(byte[] buffer) {
        return calculateMd5(buffer, (byte[][]) null);
    }

    /**
     * Calculates MD5 of the given buffer concatenated with all the following byte arrays
     * @param buffer
     * @param concatenatedWith
     * @return MD5 of empty String
     */
    private String calculateMd5(byte[] buffer, byte[]... concatenatedWith) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            digest.update(buffer);

            if (concatenatedWith != null){
                for (byte[] byteArray : concatenatedWith){
                    if (byteArray != null)
                        digest.update(byteArray);
                }
            }

            byte [] md5Bytes = digest.digest();
            String md5hash = "";
            for (int i = 0; i < md5Bytes.length; i++) {
                md5hash += Integer.toString(( md5Bytes[i] & 0xff ) + 0x100, 16).substring(1);
            }

            return md5hash;
        } catch (Exception e) {
            e.printStackTrace();
            sLogging.error(e);
            return "";
        }
    }

    @Override
    protected void onStart() {
        if (mCore != null) {
            mCore.startAlerts();
            createAlertWaiter();
        }
        super.onStart();
    }

    @Override
    protected void onStop() {
        if (mCore != null) {
            destroyAlertWaiter();
            mCore.stopAlerts();
        }
        super.onStop();
    }	
}
