package ca.yorku.eecs3311.huntassist.review;

import java.util.List;
import java.util.Map;

interface LocalRanker {
    enum Status {
        MODEL_RANKED, MODEL_DISABLED, MODEL_UNAVAILABLE, MODEL_INVALID
    }

    record Ranking(Status status, List<DefensiveAction> order) {
        public Ranking {
            order = List.copyOf(order);
        }
    }

    Ranking rank(Map<VulnerabilityModule.Signal, Boolean> signals, List<DefensiveAction> allowed);
}
