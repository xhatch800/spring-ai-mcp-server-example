package com.example.mcpnotes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class NoteServiceTest {

    private MutableClock clock;
    private NoteService service;

    @BeforeEach
    void setUp() {
        clock = new MutableClock();
        service = new NoteService(clock);
    }

    @Nested
    class Add {

        @Test
        void storesNoteWithGeneratedIdAndClockTimestamp() {
            Note note = service.add("Title", "Body", List.of("a", "b"));

            assertThat(note.id()).isEqualTo("1");
            assertThat(note.title()).isEqualTo("Title");
            assertThat(note.body()).isEqualTo("Body");
            assertThat(note.tags()).containsExactly("a", "b");
            assertThat(note.createdAt()).isEqualTo(MutableClock.START);
            assertThat(service.find("1")).contains(note);
        }

        @Test
        void idsAreSequential() {
            assertThat(service.add("One", "", null).id()).isEqualTo("1");
            assertThat(service.add("Two", "", null).id()).isEqualTo("2");
        }

        @Test
        void duplicateNotesAreAllowedAndGetDistinctIds() {
            Note first = service.add("Same", "same", List.of("x"));
            Note second = service.add("Same", "same", List.of("x"));

            assertThat(second.id()).isNotEqualTo(first.id());
            assertThat(service.list()).hasSize(2);
        }

        @Test
        void titleIsStrippedButBodyIsKeptVerbatim() {
            Note note = service.add("  Padded  ", "  keep  ", null);

            assertThat(note.title()).isEqualTo("Padded");
            assertThat(note.body()).isEqualTo("  keep  ");
        }

        @Test
        void nullBodyBecomesEmpty() {
            assertThat(service.add("Title", null, null).body()).isEmpty();
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {" ", "   ", "\t", "\n"})
        void blankTitleIsRejectedAndNothingIsStored(String title) {
            assertThatThrownBy(() -> service.add(title, "body", null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Title must not be blank.");
            assertThat(service.list()).isEmpty();
        }

        @Test
        void nullTagsBecomeEmptyList() {
            assertThat(service.add("T", "b", null).tags()).isEmpty();
        }

        @Test
        void emptyTagsStayEmpty() {
            assertThat(service.add("T", "b", List.of()).tags()).isEmpty();
        }

        @Test
        void tagsAreNormalizedDeduplicatedAndStripped() {
            List<String> raw = Arrays.asList(" Work ", "work", "IDEA", null, "", "  ", "idea", "Home");

            assertThat(service.add("T", "b", raw).tags()).containsExactly("work", "idea", "home");
        }

        @Test
        void storedTagsAreImmutable() {
            Note note = service.add("T", "b", new ArrayList<>(List.of("a")));

            assertThatThrownBy(() -> note.tags().add("b")).isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        void callerListMutationDoesNotAffectStoredNote() {
            List<String> tags = new ArrayList<>(List.of("a"));
            Note note = service.add("T", "b", tags);
            tags.add("late");

            assertThat(service.find(note.id()).orElseThrow().tags()).containsExactly("a");
        }
    }

    @Nested
    class Search {

        @BeforeEach
        void seed() {
            service.add("Shopping list", "milk and eggs", List.of("home"));
            service.add("Meeting", "Discuss the SPRING roadmap", List.of("work"));
            service.add("Ideas", "nothing here", List.of("Brainstorm", "work"));
        }

        @ParameterizedTest
        @ValueSource(strings = {"shopping", "SHOPPING", "ShOpPiNg"})
        void matchesTitleCaseInsensitively(String query) {
            assertThat(titles(service.search(query))).containsExactly("Shopping list");
        }

        @ParameterizedTest
        @ValueSource(strings = {"spring", "SPRING", "Roadmap"})
        void matchesBodyCaseInsensitively(String query) {
            assertThat(titles(service.search(query))).containsExactly("Meeting");
        }

        @ParameterizedTest
        @ValueSource(strings = {"brainstorm", "BRAINSTORM", "storm"})
        void matchesTagCaseInsensitivelyAndByFragment(String query) {
            assertThat(titles(service.search(query))).containsExactly("Ideas");
        }

        @Test
        void returnsEveryNoteThatMatchesInOldestFirstOrder() {
            assertThat(titles(service.search("work"))).containsExactly("Meeting", "Ideas");
        }

        @Test
        void noteMatchingInSeveralFieldsAppearsOnce() {
            service.add("work work", "work", List.of("work"));

            assertThat(service.search("work")).hasSize(3);
        }

        @Test
        void queryIsStripped() {
            assertThat(titles(service.search("  milk  "))).containsExactly("Shopping list");
        }

        @Test
        void noMatchReturnsEmptyList() {
            assertThat(service.search("zzz")).isEmpty();
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {" ", "\t\n"})
        void blankQueryIsRejected(String query) {
            assertThatThrownBy(() -> service.search(query))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Search query must not be blank.");
        }

        private List<String> titles(List<Note> notes) {
            return notes.stream().map(Note::title).toList();
        }
    }

    @Nested
    class Delete {

        @Test
        void removesExistingNote() {
            Note note = service.add("T", "b", null);

            assertThat(service.delete(note.id())).isTrue();
            assertThat(service.find(note.id())).isEmpty();
            assertThat(service.count()).isZero();
        }

        @Test
        void deletingTwiceReturnsFalseTheSecondTime() {
            Note note = service.add("T", "b", null);

            assertThat(service.delete(note.id())).isTrue();
            assertThat(service.delete(note.id())).isFalse();
        }

        @Test
        void unknownIdReturnsFalseAndLeavesOthersAlone() {
            service.add("T", "b", null);

            assertThat(service.delete("999")).isFalse();
            assertThat(service.count()).isEqualTo(1);
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"  "})
        void nullOrBlankIdReturnsFalse(String id) {
            service.add("T", "b", null);

            assertThat(service.delete(id)).isFalse();
            assertThat(service.count()).isEqualTo(1);
        }

        @Test
        void idIsStripped() {
            Note note = service.add("T", "b", null);

            assertThat(service.delete(" " + note.id() + " ")).isTrue();
        }

        @Test
        void deletedIdsAreNotReused() {
            service.delete(service.add("One", "", null).id());

            assertThat(service.add("Two", "", null).id()).isEqualTo("2");
        }
    }

    @Nested
    class Find {

        @Test
        void returnsNoteById() {
            Note note = service.add("T", "b", null);

            assertThat(service.find(note.id())).contains(note);
        }

        @Test
        void idIsStripped() {
            service.add("T", "b", null);

            assertThat(service.find(" 1 ")).isPresent();
        }

        @Test
        void unknownIdIsEmpty() {
            assertThat(service.find("42")).isEmpty();
        }

        @Test
        void nullIdIsEmpty() {
            assertThat(service.find(null)).isEqualTo(Optional.empty());
        }
    }

    @Nested
    class ListNotes {

        @Test
        void emptyServiceReturnsEmptyList() {
            assertThat(service.list()).isEmpty();
            assertThat(service.count()).isZero();
        }

        @Test
        void sortedByCreationTime() {
            service.add("first", "", null);
            clock.advance(Duration.ofMinutes(1));
            service.add("second", "", null);

            assertThat(service.list()).extracting(Note::title).containsExactly("first", "second");
        }

        @Test
        void createdAtWinsOverIdWhenClockMovesBackwards() {
            service.add("later", "", null);
            clock.set(MutableClock.START.minus(Duration.ofHours(1)));
            service.add("earlier", "", null);

            assertThat(service.list()).extracting(Note::title).containsExactly("earlier", "later");
        }

        @Test
        void sameTimestampFallsBackToNumericIdOrderNotLexical() {
            IntStream.rangeClosed(1, 12).forEach(i -> service.add("n" + i, "", null));

            assertThat(service.list()).extracting(Note::id)
                    .containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12");
        }

        @Test
        void listByTagMatchesCaseInsensitively() {
            service.add("a", "", List.of("Work"));
            service.add("b", "", List.of("home"));

            assertThat(service.listByTag("WORK")).extracting(Note::title).containsExactly("a");
            assertThat(service.listByTag("  home ")).extracting(Note::title).containsExactly("b");
        }

        @Test
        void listByTagRequiresExactTagNotFragment() {
            service.add("a", "", List.of("working"));

            assertThat(service.listByTag("work")).isEmpty();
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"  "})
        void listByBlankTagReturnsEverything(String tag) {
            service.add("a", "", List.of("x"));
            service.add("b", "", null);

            assertThat(service.listByTag(tag)).hasSize(2);
        }

        @Test
        void listByUnknownTagIsEmpty() {
            service.add("a", "", List.of("x"));

            assertThat(service.listByTag("nope")).isEmpty();
        }
    }

    @Nested
    class CreatedWithin {

        @Test
        void includesNotesInsideTheWindowAndTheExactBoundary() {
            Duration week = Duration.ofDays(7);
            clock.set(MutableClock.START.minus(week));
            service.add("boundary", "", null);
            clock.set(MutableClock.START.minus(Duration.ofDays(3)));
            service.add("recent", "", null);
            clock.set(MutableClock.START);
            service.add("now", "", null);

            assertThat(service.createdWithin(week)).extracting(Note::title)
                    .containsExactly("boundary", "recent", "now");
        }

        @Test
        void excludesNotesOlderThanTheWindow() {
            clock.set(MutableClock.START.minus(Duration.ofDays(7)).minusSeconds(1));
            service.add("old", "", null);
            clock.set(MutableClock.START);

            assertThat(service.createdWithin(Duration.ofDays(7))).isEmpty();
        }

        @Test
        void windowMovesWithTheClock() {
            service.add("note", "", null);
            assertThat(service.createdWithin(Duration.ofDays(1))).hasSize(1);

            clock.advance(Duration.ofDays(2));

            assertThat(service.createdWithin(Duration.ofDays(1))).isEmpty();
        }
    }

    @Nested
    class TopTags {

        @Test
        void emptyWhenNoTags() {
            service.add("a", "", null);

            assertThat(service.topTags(5)).isEmpty();
        }

        @Test
        void sortedByCountThenAlphabetically() {
            service.add("1", "", List.of("beta", "alpha"));
            service.add("2", "", List.of("beta", "gamma"));
            service.add("3", "", List.of("gamma", "beta"));

            assertThat(service.topTags(10)).containsExactly(
                    new TagCount("beta", 3), new TagCount("gamma", 2), new TagCount("alpha", 1));
        }

        @Test
        void honoursLimit() {
            service.add("1", "", List.of("a", "b", "c"));

            assertThat(service.topTags(2)).extracting(TagCount::tag).containsExactly("a", "b");
        }

        @Test
        void countsOncePerNoteBecauseTagsAreDeduplicated() {
            service.add("1", "", List.of("x", "X", "x"));

            assertThat(service.topTags(5)).containsExactly(new TagCount("x", 1));
        }
    }

    @Nested
    class Seeding {

        @Test
        void seedsThreeNotesRelativeToTheClock() {
            service.seedSamples();

            assertThat(service.list()).hasSize(3);
            assertThat(service.list()).extracting(Note::createdAt).containsExactly(
                    MutableClock.START.minus(Duration.ofDays(2)),
                    MutableClock.START.minus(Duration.ofDays(1)),
                    MutableClock.START);
            assertThat(service.list()).allSatisfy(note -> {
                assertThat(note.title()).isNotBlank();
                assertThat(note.body()).isNotBlank();
                assertThat(note.tags()).isNotEmpty();
            });
        }

        @Test
        void newNotesContinueTheIdSequence() {
            service.seedSamples();

            assertThat(service.add("T", "b", null).id()).isEqualTo("4");
        }
    }

    @Nested
    class Concurrency {

        @Test
        void concurrentAddsLoseNothingAndNeverShareAnId() throws Exception {
            int threads = 16;
            int perThread = 50;
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch start = new CountDownLatch(1);
            try {
                List<Future<List<String>>> futures = new ArrayList<>();
                for (int t = 0; t < threads; t++) {
                    int thread = t;
                    futures.add(pool.submit(() -> {
                        start.await();
                        List<String> ids = new ArrayList<>();
                        for (int i = 0; i < perThread; i++) {
                            ids.add(service.add("t" + thread + "-" + i, "body", List.of("shared")).id());
                        }
                        return ids;
                    }));
                }
                start.countDown();

                List<String> allIds = new ArrayList<>();
                for (Future<List<String>> future : futures) {
                    allIds.addAll(future.get(30, TimeUnit.SECONDS));
                }

                Set<String> distinct = allIds.stream().collect(Collectors.toSet());
                assertThat(distinct).hasSize(threads * perThread);
                assertThat(service.count()).isEqualTo(threads * perThread);
                assertThat(service.list()).hasSize(threads * perThread);
                assertThat(service.topTags(1)).containsExactly(new TagCount("shared", threads * perThread));
            } finally {
                pool.shutdownNow();
            }
        }

        @Test
        void concurrentAddsAndDeletesStayConsistent() throws Exception {
            List<String> ids = Collections.synchronizedList(new ArrayList<>());
            IntStream.range(0, 100).forEach(i -> ids.add(service.add("n" + i, "", null).id()));
            ExecutorService pool = Executors.newFixedThreadPool(8);
            CountDownLatch start = new CountDownLatch(1);
            try {
                List<Future<?>> futures = new ArrayList<>();
                for (int t = 0; t < 4; t++) {
                    futures.add(pool.submit(() -> {
                        start.await();
                        IntStream.range(0, 25).forEach(i -> service.add("extra", "", null));
                        return null;
                    }));
                    int from = t * 25;
                    futures.add(pool.submit(() -> {
                        start.await();
                        ids.subList(from, from + 25).forEach(service::delete);
                        return null;
                    }));
                }
                start.countDown();
                for (Future<?> future : futures) {
                    future.get(30, TimeUnit.SECONDS);
                }

                assertThat(service.count()).isEqualTo(100);
                assertThat(service.list()).extracting(Note::title).containsOnly("extra");
            } finally {
                pool.shutdownNow();
            }
        }
    }
}
