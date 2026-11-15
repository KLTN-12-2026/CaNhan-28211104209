package com.carrental.modules.ai.parser;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ExplicitBudgetTest {
    @Test void unsupportedExpressionsAreNotBoundAsTripTotals(){
        for(String text:new String[]{"Phí giao xe 2 triệu. Hãy tính tổng sau.","Ngân sách mười hai triệu",
                "Ngân sách hai triệu năm trăm","Không chọn ngân sách 2 triệu","Đừng chọn ngân sách 2 triệu","Tổng 2 triệu người",
                "Tổng 9007199254740992 đồng"}){
            assertFalse(ExplicitBudget.fromText(text).fixed(),text);
        }
    }
    @Test void explicitZeroIsDifferentFromAnUnchosenBudget(){
        assertEquals(new ExplicitBudget(true,0L),ExplicitBudget.fromText("Ngân sách tổng 0 đồng"));
        assertEquals(new ExplicitBudget(true,null),ExplicitBudget.fromText("Tổng 0 đồng hoặc 1 triệu"));
    }
}
