package com.carrental.modules.ai.parser;

import java.util.List;

/** Extraction is untrusted until a caller resolves catalog names and validates the full criteria. */
public interface CriteriaParser {
    record Extraction(String intent, String pickupDate, String returnDate, String pickupBranchName,
                      String returnBranchName, Integer passengers, Long budgetVndTotal,
                      String transmission, List<String> unresolvedFields, List<String> conflicts) {}
    Extraction parse(String redactedText);
}
