package com.carrental.modules.ai.itinerary;

import static org.junit.jupiter.api.Assertions.*;

import com.carrental.modules.ai.itinerary.service.ItineraryCandidateSelector;
import com.carrental.modules.ai.itinerary.service.ItineraryCandidateSelector.Excluded;
import com.carrental.modules.ai.itinerary.service.ItineraryCandidateSelector.Reason;
import com.carrental.modules.location.service.LocationService.PublicView;
import com.carrental.modules.location.service.VisitMetadata;
import com.carrental.modules.location.service.VisitMetadata.*;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Instant;
import java.util.*;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

/** G6.2: candidate set is finite, keeps the start, prefers the category, breaks ties by ID, reports exclusions. */
class ItineraryCandidateSelectorTest {
    static final Evidence EVIDENCE = new Evidence("https://example.invalid/s", Instant.parse("2027-01-01T00:00:00Z"));
    static final Set<String> CLOSED = new HashSet<>();
    static final Predicate<PublicView> OPEN = v -> !CLOSED.contains(v.id());

    static PublicView view(String id, String category, String name, Integer duration) {
        var meta = new VisitMetadata("Asia/Ho_Chi_Minh",
            Map.of(DayOfWeek.WEDNESDAY, new Hours(State.OPEN_24H, List.of())), Map.of(), duration, EVIDENCE, EVIDENCE);
        return new PublicView(id, category, category, name, BigDecimal.ONE, BigDecimal.ONE, meta);
    }
    static PublicView noMetadata(String id) { return new PublicView(id, "culture", "culture", "No facts " + id, BigDecimal.ONE, BigDecimal.ONE, null); }
    static List<String> ids(ItineraryCandidateSelector.Selection s) { return s.candidates().stream().map(PublicView::id).toList(); }

    @Test void startStaysFirstThenPreferredCategoryThenAscendingId() {
        CLOSED.clear();
        var start = view("z-start", "beach", "Bắt đầu", 30);
        var catalog = List.of(view("m", "culture", "A", 30), view("b", "beach", "Z", 30), start,
            view("k", "culture", "B", 30), view("a", "beach", "Y", 30), view("c", "food", "C", 30));
        var preferred = ItineraryCandidateSelector.select(catalog, start, "culture", OPEN);
        assertEquals(List.of("z-start", "k", "m", "a", "b", "c"), ids(preferred));
        var none = ItineraryCandidateSelector.select(catalog, start, null, OPEN);
        assertEquals(List.of("z-start", "a", "b", "c", "k", "m"), ids(none));
        assertEquals(ids(none), ids(ItineraryCandidateSelector.select(catalog, start, "  ", OPEN)), "blank category means no preference");
        assertEquals(6, none.eligibleCount());
        assertFalse(none.truncated());
    }

    @Test void selectionIsIdenticalForEveryCatalogPermutationAndRename() {
        CLOSED.clear();
        var start = view("s", "culture", "Start", 30);
        var base = new ArrayList<>(List.of(start, view("d", "beach", "D", 30), view("a", "culture", "A", 30),
            view("c", "beach", "C", 30), view("b", "food", "B", 30)));
        var expected = ids(ItineraryCandidateSelector.select(base, start, "beach", OPEN));
        var seen = new HashSet<List<String>>();
        permute(base, 0, perm -> seen.add(ids(ItineraryCandidateSelector.select(perm, start, "beach", OPEN))));
        assertEquals(Set.of(expected), seen, "120 permutations must give one selection");
        var renamed = base.stream().map(v -> new PublicView(v.id(), v.categoryId(), v.categoryName(), "Đổi tên " + (99 - v.id().charAt(0)),
            v.latitude(), v.longitude(), v.metadata())).toList();
        assertEquals(expected, ids(ItineraryCandidateSelector.select(renamed, start, "beach", OPEN)));
    }

    @Test void locationsWithoutFactsOrClosedThatDayAreExcludedWithReasons() {
        CLOSED.clear();
        CLOSED.add("closed");
        var start = view("s", "culture", "Start", 30);
        var catalog = List.of(noMetadata("nometa"), view("zero", "culture", "Zero", 0), view("nodur", "culture", "Null", null),
            view("closed", "culture", "Closed", 30), start, view("ok", "culture", "Ok", 30));
        var selection = ItineraryCandidateSelector.select(catalog, start, null, OPEN);
        assertEquals(List.of("s", "ok"), ids(selection));
        assertEquals(List.of(new Excluded("closed", Reason.NOT_OPEN_ON_DATE), new Excluded("nodur", Reason.MISSING_DURATION),
            new Excluded("nometa", Reason.MISSING_METADATA), new Excluded("zero", Reason.MISSING_DURATION)), selection.excluded());
        assertEquals(2, selection.eligibleCount());

        var lonely = ItineraryCandidateSelector.select(List.of(start, noMetadata("x")), start, null, OPEN);
        assertEquals(1, lonely.eligibleCount(), "start alone is not enough for a two-stop plan");
        CLOSED.clear();
    }

    @Test void capKeepsEightAndReportsTheRest() {
        CLOSED.clear();
        var start = view("s00", "culture", "Start", 30);
        var catalog = new ArrayList<PublicView>(List.of(start));
        for (int i = 12; i >= 1; i--) catalog.add(view(String.format("p%02d", i), i % 2 == 0 ? "beach" : "culture", "P" + i, 30));
        var selection = ItineraryCandidateSelector.select(catalog, start, "beach", OPEN);
        assertEquals(ItineraryCandidateSelector.MAX_CANDIDATES, selection.candidates().size());
        assertEquals(List.of("s00", "p02", "p04", "p06", "p08", "p10", "p12", "p01"), ids(selection));
        assertEquals(List.of("p03", "p05", "p07", "p09", "p11"), selection.truncatedIds());
        assertTrue(selection.truncated());
        assertEquals(13, selection.eligibleCount());
    }

    static void permute(List<PublicView> list, int k, java.util.function.Consumer<List<PublicView>> sink) {
        if (k == list.size()) { sink.accept(List.copyOf(list)); return; }
        for (int i = k; i < list.size(); i++) {
            Collections.swap(list, k, i);
            permute(list, k + 1, sink);
            Collections.swap(list, k, i);
        }
    }
}
