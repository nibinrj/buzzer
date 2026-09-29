package dev.nibin.buzzer.scoring.api;

import dev.nibin.buzzer.scoring.application.GetResults;
import dev.nibin.buzzer.scoring.application.GetResults.FinalResults;
import dev.nibin.buzzer.scoring.application.GetResults.LiveLeaderboard;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Results of a session. /leaderboard is the live top 10 (the same data ScoreUpdated carries, for a client that just
 * (re)connected); /final is every player, once the session has ended. Player ids are membership ids: clients map
 * them to display names with session-service's /state.
 */
@RestController
@RequestMapping("/api/results/sessions/{sessionId}")
public class ResultsController {

    private final GetResults results;

    public ResultsController(GetResults results) {
        this.results = results;
    }

    @GetMapping("/leaderboard")
    LeaderboardResponse leaderboard(@PathVariable UUID sessionId) {
        LiveLeaderboard board = results.leaderboard(sessionId);
        return new LeaderboardResponse(sessionId, board.version(), board.top().stream()
                .map(line -> new LeaderboardResponse.Line(line.rank(), line.item().playerId(), line.item().points()))
                .toList());
    }

    @GetMapping("/final")
    FinalResultsResponse finalResults(@PathVariable UUID sessionId) {
        FinalResults results = this.results.finalResults(sessionId);
        return new FinalResultsResponse(sessionId, results.endedAt(), results.players().stream()
                .map(line -> new FinalResultsResponse.Line(line.rank(), line.item().playerId(), line.item().points(),
                        line.item().answers(), line.item().correctAnswers()))
                .toList());
    }

    /** @param version the ScoreUpdated version this matches: a client keeps whichever is newer */
    public record LeaderboardResponse(UUID sessionId, long version, List<Line> top) {

        public record Line(int rank, UUID playerId, int points) {
        }
    }

    public record FinalResultsResponse(UUID sessionId, Instant endedAt, List<Line> players) {

        public record Line(int rank, UUID playerId, int points, int answers, int correctAnswers) {
        }
    }
}
