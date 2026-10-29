package br.app.btplayer;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadata;
import android.media.browse.MediaBrowser;
import android.media.session.MediaController;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Tela "Tocando Agora" inspirada no Apple Music. Mostra titulo/artista/album
 * da musica que o celular toca por Bluetooth e controla play/pausa/proxima/
 * anterior (AVRCP) pelo servico de midia Bluetooth do Android da multimidia.
 */
public class MainActivity extends Activity {

    private static final String BT_PKG = "com.android.bluetooth";
    private static final String BT_SERVICE =
            "com.android.bluetooth.avrcpcontroller.BluetoothMediaBrowserService";

    private static final int ACCENT = 0xFFFA586A;   // rosa estilo Apple Music
    private static final int GREEN = 0xFF30D158;
    private static final int ORANGE = 0xFFFF9F0A;
    private static final int GRAY = 0xFF8E8E93;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private TextView status, title, artist, album, timeLeft, timeRight;
    private IconView btnPlay;
    private LinearLayout progressFill, progressRest;

    private MediaBrowser browser;
    private MediaController controller;

    private boolean playing = false;
    private long duration = 0;
    private long basePos = 0;
    private long baseTime = 0;
    private String lastTitle = "";

    private final MediaController.Callback controllerCallback = new MediaController.Callback() {
        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            showMetadata(metadata);
        }

        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            showState(state);
        }

        @Override
        public void onSessionDestroyed() {
            controller = null;
            setStatus("Sessao encerrada. Reconectando...", ORANGE);
            scheduleReconnect();
        }
    };

    private final MediaBrowser.ConnectionCallback connectionCallback =
            new MediaBrowser.ConnectionCallback() {
                @Override
                public void onConnected() {
                    try {
                        controller = new MediaController(MainActivity.this, browser.getSessionToken());
                        controller.registerCallback(controllerCallback);
                        setStatus("● Conectado ao Bluetooth", GREEN);
                        showMetadata(controller.getMetadata());
                        showState(controller.getPlaybackState());
                    } catch (Exception e) {
                        setStatus("Erro ao abrir sessao: " + e.getMessage(), ORANGE);
                        scheduleReconnect();
                    }
                }

                @Override
                public void onConnectionSuspended() {
                    setStatus("Conexao suspensa. Reconectando...", ORANGE);
                    scheduleReconnect();
                }

                @Override
                public void onConnectionFailed() {
                    setStatus("Nao consegui acessar o servico de midia Bluetooth. "
                            + "Confira se o celular esta pareado e conectado.", ORANGE);
                    scheduleReconnect();
                }
            };

    private final Runnable reconnectTask = () -> {
        disconnect();
        connect();
    };

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            updateProgress();
            handler.postDelayed(this, 500);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        buildUi();
    }

    @Override
    protected void onStart() {
        super.onStart();
        connect();
        handler.post(ticker);
    }

    @Override
    protected void onStop() {
        handler.removeCallbacks(reconnectTask);
        handler.removeCallbacks(ticker);
        disconnect();
        super.onStop();
    }

    // ---------------------------------------------------------------- Bluetooth

    private void connect() {
        try {
            browser = new MediaBrowser(this, new ComponentName(BT_PKG, BT_SERVICE),
                    connectionCallback, null);
            browser.connect();
        } catch (Exception e) {
            setStatus("Erro: " + e.getMessage(), ORANGE);
            scheduleReconnect();
        }
    }

    private void disconnect() {
        if (controller != null) {
            try {
                controller.unregisterCallback(controllerCallback);
            } catch (Exception ignored) {
            }
            controller = null;
        }
        if (browser != null) {
            try {
                browser.disconnect();
            } catch (Exception ignored) {
            }
            browser = null;
        }
    }

    private void scheduleReconnect() {
        handler.removeCallbacks(reconnectTask);
        handler.postDelayed(reconnectTask, 5000);
    }

    // ------------------------------------------------------------------- Estado

    private void setStatus(String text, int color) {
        status.setText(text);
        status.setTextColor(color);
    }

    private void showMetadata(MediaMetadata m) {
        if (m == null) {
            title.setText("Nao esta tocando");
            artist.setText("");
            album.setText("");
            duration = 0;
            return;
        }
        CharSequence t = m.getText(MediaMetadata.METADATA_KEY_TITLE);
        CharSequence a = m.getText(MediaMetadata.METADATA_KEY_ARTIST);
        CharSequence al = m.getText(MediaMetadata.METADATA_KEY_ALBUM);
        String newTitle = t != null ? t.toString() : "Sem titulo";
        if (!newTitle.equals(lastTitle)) {
            lastTitle = newTitle;
            basePos = 0;
            baseTime = SystemClock.elapsedRealtime();
        }
        title.setText(newTitle);
        artist.setText(a != null ? a : "");
        album.setText(al != null ? al : "");
        duration = m.getLong(MediaMetadata.METADATA_KEY_DURATION);
        updateProgress();
    }

    private void showState(PlaybackState s) {
        long now = SystemClock.elapsedRealtime();
        long current = currentPosition();
        playing = s != null && s.getState() == PlaybackState.STATE_PLAYING;
        if (s != null && s.getPosition() >= 0) {
            basePos = s.getPosition();
        } else {
            basePos = current;
        }
        baseTime = now;
        btnPlay.setType(playing ? IconView.PAUSE : IconView.PLAY);
        updateProgress();
    }

    private long currentPosition() {
        long p = basePos;
        if (playing) p += SystemClock.elapsedRealtime() - baseTime;
        if (duration > 0 && p > duration) p = duration;
        return Math.max(p, 0);
    }

    private void updateProgress() {
        if (progressFill == null) return;
        long pos = currentPosition();
        float frac = duration > 0 ? Math.min(1f, (float) pos / duration) : 0f;
        setWeight(progressFill, frac * 1000f);
        setWeight(progressRest, (1f - frac) * 1000f);
        timeLeft.setText(formatTime(pos));
        timeRight.setText(duration > 0 ? "-" + formatTime(duration - pos) : "--:--");
    }

    private void setWeight(View v, float w) {
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) v.getLayoutParams();
        if (lp.weight != w) {
            lp.weight = w;
            v.setLayoutParams(lp);
        }
    }

    private String formatTime(long ms) {
        long total = Math.max(0, ms) / 1000;
        return String.format("%d:%02d", total / 60, total % 60);
    }

    // ---------------------------------------------------------------------- UI

    private void buildUi() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        boolean landscape = dm.widthPixels > dm.heightPixels;
        int artSize = landscape
                ? (int) (dm.heightPixels * 0.62f)
                : (int) Math.min(dm.widthPixels * 0.72f, dm.heightPixels * 0.38f);
        int textGravity = landscape ? Gravity.START : Gravity.CENTER_HORIZONTAL;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(landscape ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(dp(32), dp(24), dp(32), dp(24));
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFF3A3A40, 0xFF0B0B0D, 0xFF000000}));

        // Quadrado de "capa" (placeholder)
        TextView art = new TextView(this);
        art.setText("♪");
        art.setTextSize(88);
        art.setTextColor(0xCCFFFFFF);
        art.setGravity(Gravity.CENTER);
        GradientDrawable artBg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xFFFA2D48, 0xFF8E2DE2});
        artBg.setCornerRadius(dp(22));
        art.setBackground(artBg);
        art.setElevation(dp(14));
        root.addView(art, new LinearLayout.LayoutParams(artSize, artSize));

        // Coluna de informacoes + controles
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams colLp = landscape
                ? new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                : new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        if (landscape) colLp.leftMargin = dp(40); else colLp.topMargin = dp(24);
        root.addView(col, colLp);

        title = label(landscape ? 30 : 24, Color.WHITE, textGravity, true);
        title.setText("Nao esta tocando");
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.MARQUEE);
        title.setMarqueeRepeatLimit(-1);
        title.setSelected(true);
        artist = label(landscape ? 22 : 18, ACCENT, textGravity, false);
        artist.setSingleLine(true);
        artist.setEllipsize(TextUtils.TruncateAt.END);
        album = label(landscape ? 16 : 14, GRAY, textGravity, false);
        album.setSingleLine(true);
        album.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(title);
        col.addView(artist);
        col.addView(album);
        col.addView(spacer(landscape ? 28 : 20));

        // Barra de progresso
        LinearLayout track = new LinearLayout(this);
        track.setOrientation(LinearLayout.HORIZONTAL);
        GradientDrawable trackBg = new GradientDrawable();
        trackBg.setColor(0x40FFFFFF);
        trackBg.setCornerRadius(dp(3));
        track.setBackground(trackBg);
        progressFill = new LinearLayout(this);
        GradientDrawable fillBg = new GradientDrawable();
        fillBg.setColor(0xDDFFFFFF);
        fillBg.setCornerRadius(dp(3));
        progressFill.setBackground(fillBg);
        progressRest = new LinearLayout(this);
        track.addView(progressFill, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 0f));
        track.addView(progressRest, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1000f));
        col.addView(track, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(5)));

        LinearLayout times = new LinearLayout(this);
        times.setOrientation(LinearLayout.HORIZONTAL);
        timeLeft = label(13, GRAY, Gravity.START, false);
        timeLeft.setText("0:00");
        timeRight = label(13, GRAY, Gravity.END, false);
        timeRight.setText("--:--");
        times.addView(timeLeft, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        times.addView(timeRight, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams timesLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        timesLp.topMargin = dp(6);
        col.addView(times, timesLp);
        col.addView(spacer(landscape ? 20 : 12));

        // Controles
        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER);

        IconView prev = new IconView(this, IconView.PREV);
        prev.setOnClickListener(v -> {
            if (controller != null) controller.getTransportControls().skipToPrevious();
        });
        btnPlay = new IconView(this, IconView.PLAY);
        btnPlay.setOnClickListener(v -> {
            if (controller == null) return;
            if (playing) controller.getTransportControls().pause();
            else controller.getTransportControls().play();
        });
        IconView next = new IconView(this, IconView.NEXT);
        next.setOnClickListener(v -> {
            if (controller != null) controller.getTransportControls().skipToNext();
        });
        controls.addView(prev, iconLp(76));
        controls.addView(btnPlay, iconLp(100));
        controls.addView(next, iconLp(76));
        col.addView(controls, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        col.addView(spacer(12));

        status = label(13, ORANGE, Gravity.CENTER_HORIZONTAL, false);
        status.setText("Conectando...");
        status.setTextColor(GRAY);
        col.addView(status);

        setContentView(root);
    }

    private LinearLayout.LayoutParams iconLp(int sizeDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp));
        lp.leftMargin = dp(14);
        lp.rightMargin = dp(14);
        return lp;
    }

    private TextView label(int sp, int color, int gravity, boolean bold) {
        TextView tv = new TextView(this);
        tv.setTextSize(sp);
        tv.setTextColor(color);
        tv.setGravity(gravity);
        tv.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        tv.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return tv;
    }

    private View spacer(int dp) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, dp(dp)));
        return v;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    // ------------------------------------------------------------------ Icones

    /** Icone desenhado a mao (play, pausa, proxima, anterior) no estilo do iOS. */
    private static class IconView extends View {
        static final int PLAY = 0, PAUSE = 1, NEXT = 2, PREV = 3;

        private int type;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        IconView(Context c, int type) {
            super(c);
            this.type = type;
            p.setColor(Color.WHITE);
            p.setStyle(Paint.Style.FILL_AND_STROKE);
            p.setStrokeJoin(Paint.Join.ROUND);
            p.setStrokeWidth(6f);
            setClickable(true);
            setOnTouchListener((v, e) -> {
                int a = e.getActionMasked();
                if (a == MotionEvent.ACTION_DOWN) setAlpha(0.5f);
                else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) setAlpha(1f);
                return false;
            });
        }

        void setType(int t) {
            if (type != t) {
                type = t;
                invalidate();
            }
        }

        private void tri(Canvas c, float x1, float y1, float x2, float y2, float x3, float y3) {
            path.reset();
            path.moveTo(x1, y1);
            path.lineTo(x2, y2);
            path.lineTo(x3, y3);
            path.close();
            c.drawPath(path, p);
        }

        @Override
        protected void onDraw(Canvas c) {
            float u = Math.min(getWidth(), getHeight()) / 100f;
            c.save();
            c.translate(getWidth() / 2f, getHeight() / 2f);
            c.scale(u, u);
            switch (type) {
                case PLAY:
                    tri(c, -18, -28, -18, 28, 30, 0);
                    break;
                case PAUSE:
                    c.drawRoundRect(new RectF(-24, -28, -7, 28), 4, 4, p);
                    c.drawRoundRect(new RectF(7, -28, 24, 28), 4, 4, p);
                    break;
                case NEXT:
                    tri(c, -32, -22, -32, 22, 0, 0);
                    tri(c, 0, -22, 0, 22, 32, 0);
                    break;
                case PREV:
                    tri(c, 0, -22, 0, 22, -32, 0);
                    tri(c, 32, -22, 32, 22, 0, 0);
                    break;
            }
            c.restore();
        }
    }
}
