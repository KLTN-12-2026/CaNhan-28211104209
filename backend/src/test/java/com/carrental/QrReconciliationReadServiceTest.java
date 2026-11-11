package com.carrental;

import com.carrental.modules.payment.service.QrReconciliationReadService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import java.util.*;
import java.util.stream.IntStream;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class QrReconciliationReadServiceTest {
    @Test void boundedBatchQueriesAndEmptyInput() {
        for (int size : new int[]{0,1,500,501}) {
            var jdbc=mock(NamedParameterJdbcTemplate.class);
            var ids=IntStream.range(0,size).mapToObj(i->"booking-"+i).toList();
            var captured=new ArrayList<List<String>>();
            doAnswer(inv -> {
                Map<?,?> params=inv.getArgument(1);
                @SuppressWarnings("unchecked") var batch=(List<String>)params.get("ids");
                assertTrue(batch.size()<=500); assertTrue(ids.containsAll(batch)); captured.add(batch);
                return null;
            }).when(jdbc).query(anyString(),anyMap(),any(RowCallbackHandler.class));
            var result=new QrReconciliationReadService(jdbc).read(ids);
            assertEquals(size,result.size()); assertEquals(3*((size+499)/500),captured.size());
            assertEquals(new HashSet<>(ids),new HashSet<>(captured.stream().flatMap(List::stream).toList()));
            if(size==0)verifyNoInteractions(jdbc);
            else assertThrows(UnsupportedOperationException.class,()->result.get(ids.getFirst()).receipts().clear());
        }
    }
    @Test void duplicateIdsAreNotQueriedTwice() {
        var jdbc=mock(NamedParameterJdbcTemplate.class);
        assertEquals(Set.of("one"),new QrReconciliationReadService(jdbc).read(List.of("one","one")).keySet());
        verify(jdbc,times(3)).query(anyString(),eq(Map.of("ids",List.of("one"))),any(RowCallbackHandler.class));
    }
}
