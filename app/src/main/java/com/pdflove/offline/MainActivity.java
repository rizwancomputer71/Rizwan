package com.pdflove.offline;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends Activity {
    private static final int REQ_PDF = 100;
    private static final int REQ_FOLDER = 101;
    private static final String PREFS = "pdf_love_prefs";
    private static final String KEY_PDF = "pdf_uri";
    private static final String KEY_FOLDER = "folder_uri";

    private static final int SKY = Color.rgb(79, 195, 247);
    private static final int SKY_DARK = Color.rgb(2, 136, 209);
    private static final int RED = Color.rgb(255, 23, 68);
    private static final int INK = Color.rgb(22, 50, 79);
    private static final int MUTED = Color.rgb(84, 112, 135);
    private static final int SURFACE = Color.rgb(244, 251, 255);

    private Uri pdfUri;
    private Uri folderUri;
    private TextView pdfLabel;
    private TextView folderLabel;
    private TextView pageInfo;
    private TextView qualityLabel;
    private TextView scaleLabel;
    private TextView status;
    private EditText fromEdit;
    private EditText toEdit;
    private SeekBar qualityBar;
    private SeekBar scaleBar;
    private ProgressBar progress;
    private Button convertButton;
    private Button cancelButton;
    private Button shareButton;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean cancelRequested = new AtomicBoolean(false);
    private final ArrayList<Uri> lastOutputs = new ArrayList<>();
    private int detectedPages = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        restoreSelections();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private TextView text(String value, float sp, int color) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setPadding(dp(4), dp(7), dp(4), dp(7));
        return t;
    }

    private Button button(String label, int color) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextColor(Color.WHITE);
        b.setBackgroundColor(color);
        b.setAllCaps(false);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.setMargins(0, dp(6), 0, dp(6));
        b.setLayoutParams(p);
        return b;
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(SURFACE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(18), dp(18), dp(28));
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView header = text("PDF Love", 30, Color.WHITE);
        header.setGravity(Gravity.CENTER);
        header.setBackgroundColor(SKY);
        header.setPadding(dp(18), dp(22), dp(18), dp(22));
        root.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView sub = text("Offline PDF → JPG  •  Private  •  No uploads", 15, INK);
        sub.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(sub);

        Button pickPdf = button("Select PDF", RED);
        pickPdf.setOnClickListener(v -> choosePdf());
        root.addView(pickPdf);

        pdfLabel = text("No PDF selected", 14, INK);
        root.addView(pdfLabel);

        Button pickFolder = button("Choose output folder", SKY_DARK);
        pickFolder.setOnClickListener(v -> chooseFolder());
        root.addView(pickFolder);

        folderLabel = text("No output folder selected", 14, INK);
        root.addView(folderLabel);

        pageInfo = text("Pages: select a PDF to detect page count", 15, RED);
        root.addView(pageInfo);

        TextView rangeTitle = text("Page range", 17, INK);
        root.addView(rangeTitle);

        fromEdit = new EditText(this);
        fromEdit.setHint("From page (default 1)");
        fromEdit.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        fromEdit.setSingleLine(true);
        fromEdit.setText("1");
        root.addView(fromEdit, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        toEdit = new EditText(this);
        toEdit.setHint("To page (blank = last page)");
        toEdit.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        toEdit.setSingleLine(true);
        root.addView(toEdit, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        qualityLabel = text("JPG quality: 92", 15, INK);
        root.addView(qualityLabel);
        qualityBar = new SeekBar(this);
        qualityBar.setMax(40);
        qualityBar.setProgress(32);
        qualityBar.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int p, boolean fromUser) {
                qualityLabel.setText("JPG quality: " + (60 + p));
            }
        });
        root.addView(qualityBar);

        scaleLabel = text("Resolution: 2×", 15, INK);
        root.addView(scaleLabel);
        scaleBar = new SeekBar(this);
        scaleBar.setMax(2);
        scaleBar.setProgress(1);
        scaleBar.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int p, boolean fromUser) {
                scaleLabel.setText("Resolution: " + (p + 1) + "×");
            }
        });
        root.addView(scaleBar);

        convertButton = button("Convert PDF to JPG", RED);
        convertButton.setOnClickListener(v -> startConversion());
        root.addView(convertButton);

        cancelButton = button("Cancel conversion", MUTED);
        cancelButton.setVisibility(View.GONE);
        cancelButton.setOnClickListener(v -> {
            cancelRequested.set(true);
            status.setText("Cancelling after current page…");
        });
        root.addView(cancelButton);

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setProgress(0);
        root.addView(progress, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(22)));

        status = text("Ready — everything works offline.", 15, INK);
        root.addView(status);

        shareButton = button("Share converted JPGs", SKY_DARK);
        shareButton.setVisibility(View.GONE);
        shareButton.setOnClickListener(v -> shareLastOutputs());
        root.addView(shareButton);

        TextView footer = text("No Internet permission. Your PDF never leaves your phone.", 13, MUTED);
        footer.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(footer);

        setContentView(scroll);
    }

    private void choosePdf() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/pdf");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(i, REQ_PDF);
    }

    private void chooseFolder() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        startActivityForResult(i, REQ_FOLDER);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        int takeFlags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        try {
            getContentResolver().takePersistableUriPermission(uri, takeFlags);
        } catch (Exception ignored) {
            // Some document providers don't offer persistable access; current-session access still works.
        }

        SharedPreferences.Editor e = getSharedPreferences(PREFS, MODE_PRIVATE).edit();
        if (requestCode == REQ_PDF) {
            pdfUri = uri;
            e.putString(KEY_PDF, uri.toString()).apply();
            pdfLabel.setText("PDF: " + displayName(uri));
            status.setText("Reading PDF information…");
            detectPageCount();
        } else if (requestCode == REQ_FOLDER) {
            folderUri = uri;
            e.putString(KEY_FOLDER, uri.toString()).apply();
            folderLabel.setText("Output folder selected ✓");
            status.setText(pdfUri == null ? "Now select a PDF." : "Ready to convert.");
        }
    }

    private void restoreSelections() {
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        String pdf = p.getString(KEY_PDF, null);
        String folder = p.getString(KEY_FOLDER, null);
        if (pdf != null) {
            pdfUri = Uri.parse(pdf);
            pdfLabel.setText("PDF: " + displayName(pdfUri));
            detectPageCount();
        }
        if (folder != null) {
            folderUri = Uri.parse(folder);
            folderLabel.setText("Output folder selected ✓");
        }
    }

    private String displayName(Uri uri) {
        String fallback = "selected document";
        try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int ix = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (ix >= 0) return c.getString(ix);
            }
        } catch (Exception ignored) { }
        return fallback;
    }

    private void detectPageCount() {
        final Uri uri = pdfUri;
        if (uri == null) return;
        executor.execute(() -> {
            try (ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(uri, "r")) {
                if (pfd == null) throw new IOException("Cannot open PDF");
                try (PdfRenderer renderer = new PdfRenderer(pfd)) {
                    int count = renderer.getPageCount();
                    detectedPages = count;
                    runOnUiThread(() -> {
                        pageInfo.setText("Pages detected: " + count);
                        toEdit.setHint("To page (blank = " + count + ")");
                        status.setText("PDF ready. Choose an output folder, then convert.");
                    });
                }
            } catch (Exception ex) {
                detectedPages = 0;
                runOnUiThread(() -> {
                    pageInfo.setText("Could not read page count");
                    status.setText("PDF cannot be opened. It may be damaged or password-protected.");
                });
            }
        });
    }

    private int parsePositive(EditText edit, int fallback) {
        String s = edit.getText().toString().trim();
        if (s.isEmpty()) return fallback;
        try {
            int value = Integer.parseInt(s);
            return Math.max(1, value);
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private void startConversion() {
        if (pdfUri == null) {
            toast("Select a PDF first.");
            return;
        }
        if (folderUri == null) {
            toast("Choose an output folder first.");
            return;
        }
        if (detectedPages <= 0) {
            toast("PDF page count is not ready yet.");
            return;
        }

        int from = parsePositive(fromEdit, 1);
        int to = parsePositive(toEdit, detectedPages);
        from = Math.min(from, detectedPages);
        to = Math.min(Math.max(to, from), detectedPages);
        final int firstPage = from - 1;
        final int lastPage = to - 1;
        final int quality = 60 + qualityBar.getProgress();
        final int scale = 1 + scaleBar.getProgress();
        final Uri source = pdfUri;
        final Uri destinationTree = folderUri;

        cancelRequested.set(false);
        lastOutputs.clear();
        shareButton.setVisibility(View.GONE);
        convertButton.setEnabled(false);
        cancelButton.setVisibility(View.VISIBLE);
        progress.setProgress(0);
        status.setText("Starting conversion…");

        executor.execute(() -> convert(source, destinationTree, firstPage, lastPage, quality, scale));
    }

    private void convert(Uri source, Uri treeUri, int first, int last, int quality, int scale) {
        int total = last - first + 1;
        int completed = 0;
        String error = null;
        boolean cancelled = false;

        try (ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(source, "r")) {
            if (pfd == null) throw new IOException("Cannot open selected PDF");
            try (PdfRenderer renderer = new PdfRenderer(pfd)) {
                Uri parent = DocumentsContract.buildDocumentUriUsingTree(
                        treeUri, DocumentsContract.getTreeDocumentId(treeUri));
                ContentResolver resolver = getContentResolver();

                for (int index = first; index <= last; index++) {
                    if (cancelRequested.get()) {
                        cancelled = true;
                        break;
                    }

                    int humanPage = index + 1;
                    runOnUiThread(() -> status.setText("Converting page " + humanPage + "…"));

                    try (PdfRenderer.Page page = renderer.openPage(index)) {
                        int width = Math.max(1, page.getWidth() * scale);
                        int height = Math.max(1, page.getHeight() * scale);
                        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                        bitmap.eraseColor(Color.WHITE);
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);

                        Uri out = DocumentsContract.createDocument(
                                resolver,
                                parent,
                                "image/jpeg",
                                String.format(Locale.US, "PDFLove_Page_%04d.jpg", humanPage));
                        if (out == null) {
                            bitmap.recycle();
                            throw new IOException("Could not create output image");
                        }

                        try (OutputStream os = resolver.openOutputStream(out, "w")) {
                            if (os == null || !bitmap.compress(Bitmap.CompressFormat.JPEG, quality, os)) {
                                throw new IOException("Could not write JPG");
                            }
                        } finally {
                            bitmap.recycle();
                        }
                        lastOutputs.add(out);
                    }

                    completed++;
                    final int percent = Math.round((completed * 100f) / total);
                    final int done = completed;
                    runOnUiThread(() -> {
                        progress.setProgress(percent);
                        status.setText("Converted " + done + " of " + total + " pages");
                    });
                }
            }
        } catch (SecurityException ex) {
            error = "Storage permission expired. Re-select the PDF and output folder.";
        } catch (Exception ex) {
            String msg = ex.getMessage();
            error = (msg == null || msg.trim().isEmpty())
                    ? "Conversion failed. The PDF may be damaged or password-protected."
                    : "Conversion failed: " + msg;
        }

        final String finalError = error;
        final boolean finalCancelled = cancelled;
        final int finalCompleted = completed;
        runOnUiThread(() -> {
            convertButton.setEnabled(true);
            cancelButton.setVisibility(View.GONE);
            if (finalError != null) {
                status.setText(finalError);
            } else if (finalCancelled) {
                status.setText("Cancelled. " + finalCompleted + " JPG file(s) were saved.");
            } else {
                progress.setProgress(100);
                status.setText("Done ✓ " + finalCompleted + " JPG file(s) saved.");
            }
            shareButton.setVisibility(lastOutputs.isEmpty() ? View.GONE : View.VISIBLE);
        });
    }

    private void shareLastOutputs() {
        if (lastOutputs.isEmpty()) return;
        Intent share = new Intent(Intent.ACTION_SEND_MULTIPLE);
        share.setType("image/jpeg");
        share.putParcelableArrayListExtra(Intent.EXTRA_STREAM, new ArrayList<>(lastOutputs));
        share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(share, "Share JPG pages"));
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onDestroy() {
        if (isFinishing()) executor.shutdownNow();
        super.onDestroy();
    }

    private abstract static class SimpleSeekListener implements SeekBar.OnSeekBarChangeListener {
        @Override public void onStartTrackingTouch(SeekBar seekBar) { }
        @Override public void onStopTrackingTouch(SeekBar seekBar) { }
    }
}
