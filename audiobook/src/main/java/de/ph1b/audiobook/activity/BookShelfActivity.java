package de.ph1b.audiobook.activity;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v4.app.ActivityOptionsCompat;
import android.support.v4.util.Pair;
import android.support.v4.view.ViewCompat;
import android.support.v7.app.ActionBar;
import android.support.v7.widget.GridLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.support.v7.widget.Toolbar;
import android.util.DisplayMetrics;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.PopupMenu;
import android.widget.ProgressBar;

import com.afollestad.materialdialogs.MaterialDialog;
import com.getbase.floatingactionbutton.FloatingActionButton;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import de.ph1b.audiobook.R;
import de.ph1b.audiobook.adapter.BookShelfAdapter;
import de.ph1b.audiobook.dialog.BookmarkDialogFragment;
import de.ph1b.audiobook.dialog.EditBookTitleDialogFragment;
import de.ph1b.audiobook.dialog.EditCoverDialogFragment;
import de.ph1b.audiobook.mediaplayer.MediaPlayerController;
import de.ph1b.audiobook.model.Book;
import de.ph1b.audiobook.model.BookAdder;
import de.ph1b.audiobook.model.DataBaseHelper;
import de.ph1b.audiobook.service.ServiceController;
import de.ph1b.audiobook.uitools.PlayPauseDrawable;
import de.ph1b.audiobook.utils.Communication;
import de.ph1b.audiobook.utils.L;
import de.ph1b.audiobook.utils.PrefsManager;
import de.ph1b.audiobook.utils.TransitionPostponeHelper;

/**
 * Showing the shelf of all the available books and provide a navigation to each book
 *
 * @author Paul Woitaschek
 */
public class BookShelfActivity extends BaseActivity implements View.OnClickListener,
        BookShelfAdapter.OnItemClickListener {

    private static final String TAG = BookShelfActivity.class.getSimpleName();
    private static final String MALFORMED_FILE = "malformedFile";
    private static final Communication COMMUNICATION = Communication.getInstance();
    private final PlayPauseDrawable playPauseDrawable = new PlayPauseDrawable();
    private BookShelfAdapter adapter;
    private PrefsManager prefs;
    private ServiceController controller;
    private MaterialDialog noFolderWarning;
    private RecyclerView recyclerView;
    private ProgressBar recyclerReplacementView;
    private FloatingActionButton fab;
    private final Communication.SimpleBookCommunication listener = new Communication.SimpleBookCommunication() {
        @Override
        public void onBookContentChanged(@NonNull final Book book) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    adapter.updateOrAddBook(book);
                }
            });
        }

        @Override
        public void onPlayStateChanged() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    setPlayState(true);
                }
            });
        }

        @Override
        public void onScannerStateChanged() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    checkVisibilities();
                }
            });
        }

        @Override
        public void onBookSetChanged(@NonNull final List<Book> activeBooks) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    adapter.newDataSet(activeBooks);
                    checkVisibilities();
                }
            });
        }


        @Override
        public void onCurrentBookIdChanged(final long oldId) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    for (int i = 0; i < adapter.getItemCount(); i++) {
                        long itemId = adapter.getItemId(i);
                        if (itemId == oldId || itemId == prefs.getCurrentBookId()) {
                            adapter.notifyItemChanged(i);
                        }
                    }
                    checkVisibilities();
                }
            });
        }
    };
    private DataBaseHelper db;

    public static Intent malformedFileIntent(Context c, File malformedFile) {
        Intent intent = new Intent(c, BookShelfActivity.class);
        intent.putExtra(MALFORMED_FILE, malformedFile);
        intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        return intent;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // init variables
        prefs = PrefsManager.getInstance(this);
        db = DataBaseHelper.getInstance(this);
        controller = new ServiceController(this);
        noFolderWarning = new MaterialDialog.Builder(this)
                .title(R.string.no_audiobook_folders_title)
                .content(getString(R.string.no_audiobook_folders_summary_start) + "\n\n" +
                        getString(R.string.no_audiobook_folders_end))
                .positiveText(R.string.dialog_confirm)
                .callback(new MaterialDialog.ButtonCallback() {
                    @Override
                    public void onPositive(MaterialDialog dialog) {
                        startActivity(new Intent(BookShelfActivity.this, FolderOverviewActivity.class));
                    }
                })
                .cancelable(false)
                .build();

        // find views
        setContentView(R.layout.activity_book_shelf);
        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        ActionBar actionBar = getSupportActionBar();
        assert actionBar != null;
        recyclerView = (RecyclerView) findViewById(R.id.recyclerView);
        recyclerReplacementView = (ProgressBar) findViewById(R.id.recyclerReplacement);
        fab = (FloatingActionButton) findViewById(R.id.fab);

        // init views
        actionBar.setDisplayHomeAsUpEnabled(false);
        actionBar.setTitle(getString(R.string.app_name));
        fab.setIconDrawable(playPauseDrawable);
        fab.setOnClickListener(this);
        recyclerView.setHasFixedSize(true);
        recyclerView.setLayoutManager(new GridLayoutManager(this, getAmountOfColumns()));
        adapter = new BookShelfAdapter(this, this);
        recyclerView.setAdapter(adapter);

        if (savedInstanceState == null) {
            if (getIntent().hasExtra(MALFORMED_FILE)) {
                File malformedFile = (File) getIntent().getSerializableExtra(MALFORMED_FILE);
                new MaterialDialog.Builder(this)
                        .title(R.string.mal_file_title)
                        .content(getString(R.string.mal_file_message) + "\n\n" + malformedFile)
                        .show();
            }
        }
    }

    /**
     * Returns the amount of columns the main-grid will need.
     *
     * @return The amount of columns, but at least 2.
     */
    private int getAmountOfColumns() {
        DisplayMetrics displayMetrics = getResources().getDisplayMetrics();
        float widthPx = displayMetrics.widthPixels;
        float desiredPx = getResources().getDimensionPixelSize(R.dimen.desired_medium_cover);
        int columns = Math.round(widthPx / desiredPx);
        return Math.max(columns, 2);
    }

    @Override
    public void onResume() {
        super.onResume();

        // scan for files
        BookAdder.getInstance(this).scanForFiles(false);

        // show dialog if no folders are set
        boolean audioFoldersEmpty = (prefs.getCollectionFolders().size() +
                prefs.getSingleBookFolders().size()) == 0;
        boolean noFolderWarningIsShowing = noFolderWarning.isShowing();
        if (audioFoldersEmpty && !noFolderWarningIsShowing) {
            noFolderWarning.show();
        }

        // update items and set ui
        setPlayState(false);
        listener.onBookSetChanged(db.getActiveBooks());

        // register receivers
        COMMUNICATION.addBookCommunicationListener(listener);
    }

    private void setPlayState(boolean animated) {
        if (MediaPlayerController.getPlayState() == MediaPlayerController.PlayState.PLAYING) {
            playPauseDrawable.transformToPause(animated);
        } else {
            playPauseDrawable.transformToPlay(animated);
        }
    }

    @Override
    public void onActivityReenter(int resultCode, Intent data) {
        super.onActivityReenter(resultCode, data);

        final TransitionPostponeHelper postponeHelper = new TransitionPostponeHelper(this);
        postponeHelper.startPostponing(2);
        fab.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                fab.getViewTreeObserver().removeOnPreDrawListener(this);
                postponeHelper.elementDone();
                return true;
            }
        });
        recyclerView.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                recyclerView.getViewTreeObserver().removeOnPreDrawListener(this);
                postponeHelper.elementDone();
                return true;
            }
        });
    }

    private void checkVisibilities() {
        final boolean hideRecycler = adapter.getItemCount() == 0 && BookAdder.scannerActive;
        if (hideRecycler) {
            recyclerReplacementView.setVisibility(View.VISIBLE);
            recyclerView.setVisibility(View.GONE);
        } else {
            recyclerReplacementView.setVisibility(View.GONE);
            recyclerView.setVisibility(View.VISIBLE);
        }

        boolean currentBookExists = false;
        long currentBookId = prefs.getCurrentBookId();
        for (int i = 0; i < adapter.getItemCount(); i++) {
            if (currentBookId == adapter.getItemId(i)) {
                currentBookExists = true;
                break;
            }
        }

        if (currentBookExists) {
            fab.setVisibility(View.VISIBLE);
        } else {
            fab.setVisibility(View.GONE);
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.book_shelf, menu);

        // sets menu item visible if there is a current book
        MenuItem currentPlaying = menu.findItem(R.id.action_current);
        currentPlaying.setVisible(db.getBook(prefs.getCurrentBookId()) != null);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.action_settings:
                startActivity(new Intent(this, SettingsActivity.class));
                return true;
            case R.id.action_current:
                startBookPlay();
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    private void startBookPlay() {
        L.i(TAG, "startBookPlay");
        Book currentBook = db.getBook(prefs.getCurrentBookId());
        if (currentBook != null) {
            List<Pair<View, String>> sharedElements = new ArrayList<>(2);

            BookShelfAdapter.ViewHolder viewHolder = (BookShelfAdapter.ViewHolder) recyclerView.findViewHolderForItemId(currentBook.getId());
            if (viewHolder != null) {
                sharedElements.add(new Pair<View, String>(viewHolder.coverView, ViewCompat.getTransitionName(viewHolder.coverView)));
            }
            sharedElements.add(new Pair<View, String>(fab, ViewCompat.getTransitionName(fab)));

            Pair[] pairs = sharedElements.toArray(new Pair[sharedElements.size()]);
            @SuppressWarnings("unchecked")
            Bundle opts = ActivityOptionsCompat.makeSceneTransitionAnimation(this, pairs).toBundle();
            ActivityCompat.startActivity(this, BookPlayActivity.newIntent(this, prefs.getCurrentBookId()), opts);
        }
    }

    @Override
    public void onClick(View view) {
        switch (view.getId()) {
            case R.id.fab:
                controller.playPause();
                break;
            default:
                break;
        }
    }

    @Override
    public void onPause() {
        super.onPause();

        COMMUNICATION.removeBookCommunicationListener(listener);
    }

    @Override
    public void onCoverClicked(int position) {
        prefs.setCurrentBookIdAndInform(adapter.getItemId(position));
        startBookPlay();
    }

    @Override
    public void onMenuClicked(final int position, final View editBook) {
        PopupMenu popupMenu = new PopupMenu(BookShelfActivity.this, editBook);
        popupMenu.inflate(R.menu.bookshelf_popup);
        popupMenu.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                final Book book = adapter.getItem(position);
                switch (item.getItemId()) {
                    case R.id.edit_cover:
                        EditCoverDialogFragment fragment = EditCoverDialogFragment.newInstance(book);
                        fragment.setOnEditBookFinished(new EditCoverDialogFragment.OnEditBookFinished() {
                            @Override
                            public void onEditBookFinished() {
                                // this is necessary for the cover update
                                adapter.notifyItemAtIdChanged(book.getId());
                            }
                        });
                        fragment.show(getSupportFragmentManager(), EditCoverDialogFragment.TAG);
                        return true;
                    case R.id.edit_title:
                        EditBookTitleDialogFragment editBookTitle = EditBookTitleDialogFragment.newInstance(book.getName());
                        editBookTitle.setOnTextChangedListener(new EditBookTitleDialogFragment.OnTextChanged() {
                            @Override
                            public void onTitleChanged(@NonNull String newTitle) {
                                //noinspection SynchronizeOnNonFinalField
                                synchronized (db) {
                                    Book dbBook = db.getBook(book.getId());
                                    if (dbBook != null) {
                                        dbBook.setName(newTitle);
                                        db.updateBook(dbBook);
                                    }
                                }
                            }
                        });
                        editBookTitle.show(getSupportFragmentManager(), EditBookTitleDialogFragment.TAG);
                        return true;
                    case R.id.bookmark:
                        BookmarkDialogFragment.newInstance(adapter.getItemId(position))
                                .show(getSupportFragmentManager(), TAG);
                        return true;
                    default:
                        return false;
                }
            }
        });
        popupMenu.show();
    }
}
