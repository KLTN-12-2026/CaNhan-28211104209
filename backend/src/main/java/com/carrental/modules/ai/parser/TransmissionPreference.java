package com.carrental.modules.ai.parser;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

record TransmissionPreference(String value, boolean ambiguous) {
    private static final Pattern AUTOMATIC_TERMS=Pattern.compile("\\b(?:tu\\s*dong|automatic|(?<!android )auto)\\b");
    private static final Pattern MANUAL_TERMS=Pattern.compile("\\b(?:so\\s*san|so\\s*tay|manual)\\b");
    private static final Pattern NEGATION_SUFFIX=Pattern.compile("(?:^|\\s)(?:khong|not|tranh|loai tru)\\b.*$");
    private static final Pattern REQUEST_AFTER_CONJUNCTION=Pattern.compile(
        "\\b(?:va|and)\\s+(?=(?:toi\\s+)?(?:muon|can|thich|chon|uu\\s+tien|want|need|prefer)\\b)");
    static TransmissionPreference fromText(String text){
        String normalized=normalizeWords(text);
        var automatic=mentions(AUTOMATIC_TERMS,normalized);
        var manual=mentions(MANUAL_TERMS,normalized);
        if(automatic.positive() && manual.positive() && !automatic.negative() && !manual.negative()
            && normalized.matches(".*\\b(?:deu duoc|loai nao cung duoc|either is fine)\\b.*")
            && !normalized.contains("bat buoc"))
            return new TransmissionPreference(null,false);
        if((automatic.positive() && manual.positive()) || automatic.contradicted() || manual.contradicted())
            return new TransmissionPreference(null,true);
        if(!automatic.positive() && !manual.positive())
            return new TransmissionPreference(null,automatic.negative() || manual.negative());
        String explicit=automatic.positive()?"AUTO":"MANUAL";
        return new TransmissionPreference(explicit,false);
    }
    private record Mentions(boolean positive,boolean negative) {
        boolean contradicted(){return positive && negative;}
    }
    private static Mentions mentions(Pattern terms,String text){
        boolean positive=false,negative=false;
        Matcher matcher=terms.matcher(text);
        while(matcher.find()){
            int clauseStart=text.lastIndexOf('|',Math.max(0,matcher.start()-1))+1;
            if(NEGATION_SUFFIX.matcher(text.substring(clauseStart,matcher.start())).find())negative=true;
            else positive=true;
        }
        return new Mentions(positive,negative);
    }
    private static String normalizeWords(String text){
        String negative=Normalizer.normalize(text,Normalizer.Form.NFC).replaceAll("(?iuU)\\bđừng\\b","không");
        String normalized=Normalizer.normalize(negative,Normalizer.Form.NFD).replaceAll("\\p{M}","")
            .toLowerCase(Locale.ROOT).replace('đ','d').replaceAll("[,.!?;]+"," | ")
            .replaceAll("\\b(?:nhung|ma|tuy nhien)\\b"," | ");
        normalized=REQUEST_AFTER_CONJUNCTION.matcher(normalized).replaceAll(" | ");
        return normalized.replaceAll("[^a-z0-9|]+"," ").trim();
    }
}
