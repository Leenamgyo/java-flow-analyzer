package analyzer.model;

import java.util.ArrayList;
import java.util.List;

/** 요청 스펙: 이름이 확인된 Request Parameter 를 이름별로 모은 것 (P* 읽기 위치들의 집계). */
public final class RequestSpecEntry {
    public String id;
    public String name;
    public final List<String> types = new ArrayList<>();
    public final List<String> defaultValues = new ArrayList<>();
    /** Spring 어노테이션에서 확인된 required 값 (Legacy 읽기는 소스로 확인 불가 → 비어 있음) */
    public final List<Boolean> required = new ArrayList<>();
    public final List<String> extractions = new ArrayList<>();
    public final List<String> parameterIds = new ArrayList<>();
    public final List<String> methodIds = new ArrayList<>();
    /** 이 파라미터 값이 출처인 바인딩 (B*) */
    public final List<String> bindingIds = new ArrayList<>();
}
