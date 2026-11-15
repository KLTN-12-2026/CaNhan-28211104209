package com.carrental.modules.ai.parser;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.OptionalLong;
import java.util.regex.Pattern;

/** A fixed null asks for clarification. An unfixed value leaves the extraction to Qwen. */
record ExplicitBudget(boolean fixed, Long value) {
    private static final ExplicitBudget ASK=new ExplicitBudget(true,null);
    private static final ExplicitBudget UNBOUND=new ExplicitBudget(false,null);
    private static final List<String> NUMBERS=List.of("khong","mot","hai","ba","bon","nam","sau","bay","tam","chin","muoi");
    private static final Pattern MONEY=Pattern.compile(
        "(?<![a-z0-9.,])([0-9]+(?:[.,][0-9]+)*|"+String.join("|",NUMBERS)+")\\s*"+
        "(trieu|nghin|dong|vnd)\\b(?:\\s+(ruoi)\\b)?");
    private static final Pattern TOTAL_PREFIX=Pattern.compile("\\b(?:ngan sach(?: tong)?|tong(?: ngan sach| chi phi| tien)?|toan ky|ca chuyen|toan chuyen)\\s*(?:(?:la|bang|toi da|khong qua|:|=)\\s*)*$");
    private static final Pattern SPENDING=Pattern.compile("\\b(?:danh|du tru|chi)\\s*$");
    private static final Pattern TRIP_SUFFIX=Pattern.compile("^\\s*(?:(?:dong|vnd)\\s+)?cho\\s+(?:ca ky|toan ky|ca chuyen|toan chuyen)\\b");
    private static final Pattern UNCERTAIN=Pattern.compile("\\b(?:hoac|hay la|chua chot|chua chon|chua biet|chua quyet|moi ngay|mot ngay|hang ngay)\\b|/\\s*ngay");
    private static final Pattern NUMBER_WORD=Pattern.compile("\\b(?:muoi|tram|linh|le|nghin|trieu|[0-9]+)\\s*$");
    private static final Pattern DAILY=Pattern.compile("\\b(?:moi ngay|mot ngay|hang ngay)\\b|/\\s*ngay\\b");
    private static final Pattern NO_TOTAL=Pattern.compile("\\b(?:chua (?:co|biet|chot|quyet) (?:muc )?ngan sach(?: tong)?|ngan sach(?: tong)?(?: (?:cua )?toi)? chua (?:co|chot|biet|quyet|chon))\\b");
    private static final Pattern ALTERNATIVES=Pattern.compile("\\s*[,;]?\\s*(?:hoac|hay)(?: la)?\\s*(?:muc\\s+)?");
    private static final Pattern DAILY_SHORT=Pattern.compile("[0-9][0-9.,]*\\s*(?:k|tr|d|dong|vnd)?\\s*/\\s*ngay\\b|\\b(?:moi ngay|hang ngay)\\s*[:=]?\\s*[0-9]");
    private static final Pattern SHARED_UNIT=Pattern.compile("\\b[0-9]+\\s+(?:hoac|hay)\\s+[0-9]+(?:[.,][0-9]+)*\\s*(?:trieu|nghin|dong|vnd)\\b");
    private static final Pattern CLAUSE=Pattern.compile("[;!?]|[,.](?=\\s)");

    static ExplicitBudget fromText(String text){
        String words=fold(text);
        if(NO_TOTAL.matcher(words).find()||SHARED_UNIT.matcher(words).find())
            return ASK;
        var amounts=MONEY.matcher(words);
        int previousEnd=-1;
        boolean dailyOnly=false;
        while(amounts.find()){
            if(previousEnd>=0 && ALTERNATIVES.matcher(words.substring(previousEnd,amounts.start())).matches())
                return ASK;
            previousEnd=amounts.end();
            if(DAILY.matcher(words.substring(previousEnd).stripLeading()).lookingAt())dailyOnly=true;
        }
        OptionalLong total=OptionalLong.empty();
        for(String clause:CLAUSE.split(words)){
            var parsed=clauseTotal(clause);
            if(parsed.isEmpty())continue;
            if(total.isPresent() && total.getAsLong()!=parsed.getAsLong())return ASK;
            total=parsed;
        }
        if(total.isPresent())return new ExplicitBudget(true,total.getAsLong());
        return dailyOnly||DAILY_SHORT.matcher(words).find()?ASK:UNBOUND;
    }

    private static String fold(String text){
        String normalized=Normalizer.normalize(text,Normalizer.Form.NFC)
            .replaceAll("(?iuU)\\bngàn\\b","nghìn").replaceAll("(?iuU)\\bđừng\\b","không");
        return Normalizer.normalize(normalized,Normalizer.Form.NFD).replaceAll("\\p{M}","")
            .toLowerCase(Locale.ROOT).replace('đ','d');
    }

    private static OptionalLong clauseTotal(String words){
        if(UNCERTAIN.matcher(words).find())return OptionalLong.empty();
        var match=MONEY.matcher(words);
        if(!match.find())return OptionalLong.empty();
        int start=match.start(),end=match.end();
        String number=match.group(1),unit=match.group(2);
        boolean half=match.group(3)!=null;
        if(match.find()||NUMBER_WORD.matcher(words.substring(0,start)).find())return OptionalLong.empty();
        // A partially understood compound number must not become a smaller total.
        if(words.substring(end).matches("(?s)^\\s*(?:[0-9]|(?:mot|hai|ba|bon|nam|sau|bay|tam|chin|muoi|tram|ruoi)\\b).*"))
            return OptionalLong.empty();
        String before=words.substring(0,start);
        if(before.replace("khong qua","").matches("(?s).*\\b(?:khong|chua|tranh|loai tru)\\b.*"))return OptionalLong.empty();
        if(words.substring(end).matches("(?s)^\\s*(?:nguoi|khach|cho|km|gio|ngay|dem|nghiep)\\b.*"))return OptionalLong.empty();
        if(!TOTAL_PREFIX.matcher(before).find()&&!(SPENDING.matcher(before).find()&&TRIP_SUFFIX.matcher(words.substring(end)).find()))
            return OptionalLong.empty();
        boolean baseUnit=unit.equals("dong")||unit.equals("vnd");
        int wordNumber=NUMBERS.indexOf(number);
        if(wordNumber>=0)number=Integer.toString(wordNumber);
        if(number.matches("[0-9]{1,3}(?:[.,][0-9]{3})+")&&baseUnit)number=number.replaceAll("[.,]","");
        if(!number.matches(baseUnit?"[0-9]+":"[0-9]+(?:[.,][0-9]{1,2})?"))return OptionalLong.empty();
        BigDecimal value=new BigDecimal(number.replace(',','.'));
        if(half)value=value.add(new BigDecimal("0.5"));
        value=value.multiply(BigDecimal.valueOf(baseUnit?1:unit.equals("trieu")?1_000_000:1_000));
        if(value.stripTrailingZeros().scale()>0 || value.compareTo(BigDecimal.valueOf(9007199254740991L))>0)
            return OptionalLong.empty();
        return OptionalLong.of(value.longValueExact());
    }
}
