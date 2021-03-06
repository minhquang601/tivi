/*
 * Copyright 2017 Google, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package app.tivi.tmdb

import app.tivi.data.DatabaseTransactionRunner
import app.tivi.data.daos.EntityInserter
import app.tivi.data.daos.EpisodesDao
import app.tivi.data.daos.SeasonsDao
import app.tivi.data.daos.TiviShowDao
import app.tivi.data.entities.Episode
import app.tivi.extensions.fetchBodyWithRetry
import com.uwetrottmann.tmdb2.Tmdb
import com.uwetrottmann.tmdb2.entities.TvEpisode
import org.threeten.bp.OffsetDateTime
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TmdbEpisodeFetcher @Inject constructor(
    private val tmdb: Tmdb,
    private val entityInserter: EntityInserter,
    private val showDao: TiviShowDao,
    private val seasonsDao: SeasonsDao,
    private val episodesDao: EpisodesDao,
    private val transactionRunner: DatabaseTransactionRunner
) {
    suspend fun updateEpisodeData(episodeId: Long) {
        val episode = episodesDao.episodeWithId(episodeId)
                ?: throw IllegalArgumentException("Episode with id[$episodeId] does not exist")
        val season = seasonsDao.seasonWithId(episode.seasonId)
                ?: throw IllegalArgumentException("Season with id[${episode.seasonId}] does not exist")
        val show = showDao.getShowWithId(season.showId)
                ?: throw IllegalArgumentException("Show with id[${season.showId}] does not exist")

        val response = tmdb.tvEpisodesService()
                .episode(show.tmdbId!!, season.number!!, episode.number!!)
                .fetchBodyWithRetry()

        transactionRunner.runInTransaction {
            upsertEpisode(season.id!!, response)
        }
    }

    private fun upsertEpisode(seasonId: Long, tmdbEpisode: TvEpisode) {
        (episodesDao.episodeWithTmdbId(tmdbEpisode.id) ?: Episode(seasonId = seasonId)).apply {
            updateProperty(this::tmdbId, tmdbEpisode.id)
            updateProperty(this::title, tmdbEpisode.name, false)
            updateProperty(this::number, tmdbEpisode.episode_number, false)
            updateProperty(this::summary, tmdbEpisode.overview, false)
            updateProperty(this::tmdbBackdropPath, tmdbEpisode.still_path)
            lastTmdbUpdate = OffsetDateTime.now()
        }.also {
            entityInserter.insertOrUpdate(episodesDao, it)
        }
    }
}