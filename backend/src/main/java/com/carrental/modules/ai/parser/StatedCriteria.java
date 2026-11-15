package com.carrental.modules.ai.parser;

import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;

/** Fixed fields supported directly by the request. A present null means ask the renter. */
final class StatedCriteria {
    static final String PICKUP="pickupBranchName", RETURN="returnBranchName", PEOPLE="passengers";
    private static final String DATE="(?:[0-9]{4} [0-9]{1,2} [0-9]{1,2}|[0-9]{1,2} [0-9]{1,2}(?: [0-9]{4})?)";
    private static final Pattern ROLE=Pattern.compile("^(?:toi )?(nhan|tra)(?: xe)? (.+)$");
    private static final Pattern OTHER_ROLE=Pattern.compile(" (?:roi |va )?(?:nhan|tra)(?: xe)? (?=(?:tai |o |kdrive |chi nhanh |[0-9]))");
    private static final Pattern UNKNOWN=Pattern.compile("\\bchua (?:chon|quyet|biet) (?:chi nhanh|diem|noi) (nhan|tra)\\b");
    private static final Pattern UNSET=Pattern.compile("\\b(?:chi nhanh|diem|noi) (nhan|tra) (?:van |dang )?(?:chua (?:chon|chot|quyet|biet)|de trong)\\b");
    private static final Pattern BOTH_UNSET=Pattern.compile("\\b(?:diem|noi|chi nhanh) nhan (?:va|lan) (?:(?:diem|noi|chi nhanh) )?tra (?:van |dang )?(?:de trong|chua (?:chon|chot|quyet|biet))\\b");
    private static final String NUMBER="(?:[0-9]+|hai|ba|bon|tu|nam|sau|bay|tam|chin)";
    private static final Pattern CHOICE=Pattern.compile("\\b"+NUMBER+"(?:\\s*nguoi)? (?:hoac|hay) "+NUMBER+"\\s*nguoi\\b");
    private static final Pattern UNCERTAIN_COUNT=Pattern.compile("\\bchua (?:biet|chot|quyet) (?:co )?(?:bao nhieu nguoi|so (?:luong )?nguoi)\\b");
    private static final Pattern NEGATED=Pattern.compile("\\b(?:khong|tranh|loai tru|thay doi|doi y|thay vi)\\b");
    private static final String BOTH="(?:(?:ca )?hai (?:dau|chieu)|nhan (?:lan |va )?tra)";

    static Map<String,String> from(String text,List<String> mentionedNames){
        Map<String,String> fields=new LinkedHashMap<>();
        if(words(text).matches(".*\\b(?:doi y|thay doi|thay vi|dinh chinh|da chot|chot lai)\\b.*"))return fields;
        for(String part:text.split("(?iuU)[;!?\\n]|,(?!\\s*(?:hoặc|hoac|hay)\\b)|\\.(?=\\s|$)")){
            // Keep Vietnamese "đừng" (don't) distinct from "dùng" (use) before accent folding.
            String clause=words(Normalizer.normalize(part,Normalizer.Form.NFC).replaceAll("(?iuU)\\bđừng\\b","không"));
            if(mentionedNames.size()==1){
                String literal=namePattern(mentionedNames.get(0),clause.contains("kdrive "));
                String rejection="(?:toi )?(?:khong(?: muon)?|tranh|loai tru)(?: chon)? ";
                if(clause.matches(rejection+"(?:"+literal+" (?:cho )?"+BOTH+"|"+BOTH+" (?:xe )?(?:tai |o )?"+literal+")")){
                    merge(fields,PICKUP,null);
                    merge(fields,RETURN,null);
                    continue;
                }
                for(String role:List.of("nhan","tra")){
                    if(clause.matches(rejection+role+" (?:xe )?(?:tai |o )?(?:chi nhanh )?"+literal+"(?: ngay "+DATE+")?"))
                        merge(fields,key(role),null);
                }
            }
            if(NEGATED.matcher(clause).find())continue;
            var unknown=UNKNOWN.matcher(clause);
            while(unknown.find())merge(fields,key(unknown.group(1)),null);
            var unset=UNSET.matcher(clause);
            while(unset.find())merge(fields,key(unset.group(1)),null);
            if(BOTH_UNSET.matcher(clause).find()){merge(fields,PICKUP,null);merge(fields,RETURN,null);}
            var role=ROLE.matcher(clause);
            if(role.matches()){
                String tail=role.group(2);
                var boundary=OTHER_ROLE.matcher(tail);
                if(boundary.find()){
                    addRole(fields,key(role.group(1)),tail.substring(0,boundary.start()),mentionedNames);
                    var second=ROLE.matcher(tail.substring(boundary.start()).strip().replaceFirst("^(?:roi|va) ",""));
                    if(second.matches())addRole(fields,key(second.group(1)),second.group(2),mentionedNames);
                }else addRole(fields,key(role.group(1)),tail,mentionedNames);
            }
            if(mentionedNames.size()==1 && !clause.matches(".*\\b(?:hoac|hay)\\b.*")){
                String name=mentionedNames.get(0);
                String literal=namePattern(name,clause.contains("kdrive "));
                if(clause.matches(".*\\b"+literal+" (?:(?:la noi|cho) )?"+BOTH+"\\b.*") ||
                    clause.matches(".*\\b"+literal+" nhan (?:ngay )?"+DATE+" tra (?:ngay )?"+DATE+"(?: |$).*") ||
                    clause.matches("(?:toi )?"+BOTH+" (?:xe )?(?:tai |o )?(?:chi nhanh )?"+literal+"(?: (?:tu|ngay) .*)?")){
                    merge(fields,PICKUP,name);
                    merge(fields,RETURN,name);
                }
            }
            if(CHOICE.matcher(clause).find() || UNCERTAIN_COUNT.matcher(clause).find()){
                merge(fields,PEOPLE,null);
            }
        }
        return fields;
    }
    private static String key(String role){return "nhan".equals(role)?PICKUP:RETURN;}
    private static void merge(Map<String,String> fields,String key,String value){
        if(fields.containsKey(key)&&!Objects.equals(fields.get(key),value))fields.put(key,null);
        else fields.put(key,value);
    }
    private static void addRole(Map<String,String> fields,String key,String tail,List<String> names){
        String place=tail.replaceFirst("^"+DATE+" ","").replaceFirst("^(?:tai |o )","")
            .replaceFirst(" (?:ngay |tu |vao )?"+DATE+"(?: .*)?$","").strip();
        if(place.isEmpty()||place.matches(DATE)||place.matches("(?:tai )?do"))return;
        if(place.matches(".*\\b(?:hoac|hay)\\b.*")){
            if(place.contains("kdrive")||names.stream().anyMatch(n->mentioned(place,n)))merge(fields,key,null);
            return;
        }
        var matches=names.stream().filter(n->place.matches("(?:chi nhanh )?"+namePattern(n,false))).toList();
        if(matches.size()==1)merge(fields,key,matches.get(0));
        else if(place.matches("^(?:kdrive|chi nhanh|gan) .+"))merge(fields,key,null);
    }
    static String words(String text){
        return Normalizer.normalize(text,Normalizer.Form.NFD).replaceAll("\\p{M}","")
            .toLowerCase(Locale.ROOT).replace('đ','d').replaceAll("[^a-z0-9]+"," ").strip();
    }
    private static String namePattern(String name,boolean requireBrand){
        String literal=words(name).replaceFirst("^kdrive\\s*","").strip();
        if(literal.isEmpty())return "(?!)";
        return (requireBrand?"kdrive\\s*":"(?:kdrive\\s*)?")+String.join("\\s*",Arrays.stream(literal.split(" ")).map(Pattern::quote).toList());
    }
    static boolean mentioned(String words,String name){
        return Pattern.compile("(?<![a-z0-9])(?:nhantra|nhan|tra)?"+namePattern(name,false)+"(?![a-z0-9])").matcher(words).find();
    }
    /** A place listed after both dated roles can corroborate a matching pair; it never supplies a missing role. */
    static boolean confirmsPair(String text,String branchName){
        if(words(text).matches(".*\\b(?:hoac|hay|khong|doi y|thay doi|thay vi|dinh chinh)\\b.*"))return false;
        var parts=text.split("[,;!?\\n]|\\.(?=\\s|$)");
        for(int i=2;i<parts.length;i++){
            if(words(parts[i-2]).matches("(?:ngay )?nhan (?:ngay )?"+DATE)
                && words(parts[i-1]).matches("(?:ngay )?tra (?:ngay )?"+DATE)
                && words(parts[i]).matches("(?:chi nhanh )?"+namePattern(branchName,false)))return true;
        }
        return false;
    }
    private StatedCriteria() {}
}
