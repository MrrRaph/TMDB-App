package com.tmdbapp.viewmodels;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import androidx.paging.LivePagedListBuilder;
import androidx.paging.PagedList;
import androidx.preference.PreferenceManager;
import com.tmdbapp.R;
import com.tmdbapp.api.APIClient;
import com.tmdbapp.api.MovieAPI;
import com.tmdbapp.data.DataRepository;
import com.tmdbapp.models.MovieModel;
import com.tmdbapp.utils.date.DateUtils;
import com.tmdbapp.utils.network.NetworkUtils;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.disposables.Disposable;
import io.reactivex.schedulers.Schedulers;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

import java.lang.ref.WeakReference;
import java.util.ArrayList;

public class MovieViewModel extends ViewModel {
    private static final int PAGE_SIZE = 20;
    private static final int TOTAL_PAGES = 3;
    private static final String DEFAULT_MOVIE_LIMIT = "50";
    private static final boolean PLACEHOLDERS = true;
    private final DataRepository repository;
    private final MovieAPI movieAPI;
    private final MutableLiveData<Boolean> firstTime = new MutableLiveData<>();
    private final MutableLiveData<Boolean> refreshing = new MutableLiveData<>();
    private final CompositeDisposable compositeDisposable = new CompositeDisposable();
    private WeakReference<Context> contextWeakReference;
    private SharedPreferences preferences;
    private boolean inactiveAll;

    public MovieViewModel(DataRepository repository) {
        this.repository = repository;
        this.movieAPI = APIClient.getClient(APIClient.TMDB_URL);
    }

    public LiveData<PagedList<MovieModel>> getPopularMovies(Context context) {
        this.contextWeakReference = new WeakReference<>(context);
        this.preferences = PreferenceManager.getDefaultSharedPreferences(this.contextWeakReference.get());

        getPopularMoviesOnline();

        int movieLimit = Integer.parseInt(DEFAULT_MOVIE_LIMIT);
        String prefKeyLimit = context.getString(R.string.pref_key_limit);
        String preferencesLimit = this.preferences.getString(prefKeyLimit, DEFAULT_MOVIE_LIMIT);

        if (preferencesLimit != null)
            movieLimit = Integer.parseInt(preferencesLimit);

        return new LivePagedListBuilder<>(this.repository.getMovies(movieLimit),
                                          new PagedList.Config.Builder()
                                                              .setPageSize(PAGE_SIZE)
                                                              .setEnablePlaceholders(PLACEHOLDERS)
                                                              .build())
                                          .build();
    }

    public void getPopularMoviesOnline() {
        if (NetworkUtils.isNetworkAvailable(this.contextWeakReference.get())) {
            for (int page = 1; page < TOTAL_PAGES; ++page) {
                Disposable disposable =
                        movieAPI.getPopular(APIClient.API_KEY_VALUE, APIClient.LANGUAGE, page)
                                .subscribeOn(Schedulers.io())
                                .observeOn(AndroidSchedulers.mainThread())
                                .doOnSubscribe(this.compositeDisposable::add)
                                .subscribe(
                                        movies -> {
                                            preferences.edit()
                                                       .putBoolean(contextWeakReference.get()
                                                                                       .getString(R.string.pref_key_first_time), false)
                                                       .apply();
                                            firstTime.setValue(false);

                                            if (!inactiveAll) {
                                                repository.inactiveAll();
                                                inactiveAll = true;
                                            }

                                            if (movies != null)
                                                for (MovieModel movie : movies) {
                                                    boolean isFavorite = repository.isMovieFavorite(movie.getId());
                                                    movie.setFavorite(isFavorite);
                                                    movie.setActive(true);
                                                    repository.insert(movie);
                                                }

                                            setLastUpdateNow();
                                            refreshing.setValue(false);
                                }, throwable -> {
                                    String errorMessage;
                                    if (throwable.getMessage() == null)
                                        errorMessage = "Unknown Error";
                                    else
                                        errorMessage = throwable.getMessage();

                                    refreshing.setValue(false);
                                    Log.e("getPopularMoviesOnline", errorMessage);
                                });
            }
        } else {
            refreshing.setValue(false);
            firstTime.setValue(preferences.getBoolean(contextWeakReference.get()
                                                                           .getString(R.string.pref_key_first_time), true));
            Log.d("getPopularMoviesOnline", "No connection.");
        }
    }

    public String getLastUpdate() {
        if (NetworkUtils.isNetworkAvailable(this.contextWeakReference.get())) return "";
        return this.preferences.getString(this.contextWeakReference.get().getString(R.string.pref_key_last_update), "");
    }

    private void setLastUpdateNow() {
        this.preferences.edit()
                        .putString(this.contextWeakReference.get()
                                                            .getString(R.string.pref_key_last_update), DateUtils.getCurrentDateTime())
                        .apply();
    }

    public MutableLiveData<Boolean> isFirstTime() {
        return this.firstTime;
    }

    public MutableLiveData<Boolean> isRefreshing() {
        return this.refreshing;
    }

    public CompositeDisposable getCompositeDisposable() {
        return this.compositeDisposable;
    }
}
