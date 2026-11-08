package analyzer.model;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** 공통 모델의 ID 참조 무결성과 제어 블록 부모·자식 관계를 검증한다. */
public final class ModelValidator {
    private ModelValidator() {
    }

    public static List<String> validate(AnalysisResult r) {
        List<String> e = new ArrayList<>();
        if (r.clazz(r.targetClassId) == null) {
            e.add("target-class-id 없음: " + r.targetClassId);
        }
        if (r.method(r.entryMethodId) == null) {
            e.add("entry-method-id 없음: " + r.entryMethodId);
        }
        for (ClassInfo c : r.classes()) {
            for (String mid : c.methodIds) {
                MethodInfo m = r.method(mid);
                if (m == null) {
                    e.add(c.id + ": 메서드 " + mid + " 없음");
                } else if (!m.classId.equals(c.id)) {
                    e.add(mid + ": class-id 불일치 " + m.classId + " != " + c.id);
                }
            }
        }
        for (MethodInfo m : r.methods()) {
            if (r.clazz(m.classId) == null) {
                e.add(m.id + ": class " + m.classId + " 없음");
            }
            if (m.expandedAt != null && r.callSite(m.expandedAt) == null) {
                e.add(m.id + ": expanded-at " + m.expandedAt + " 없음");
            }
        }
        for (CallSiteInfo s : r.callSites) {
            MethodInfo caller = r.method(s.callerMethodId);
            if (caller == null) {
                e.add(s.id + ": caller " + s.callerMethodId + " 없음");
            } else if (!caller.analyzed) {
                e.add(s.id + ": 분석되지 않은 메서드 " + caller.id + " 에 호출 위치가 존재");
            }
            if (s.targetMethodId != null && r.method(s.targetMethodId) == null) {
                e.add(s.id + ": target " + s.targetMethodId + " 없음");
            }
            if (s.targetMethodId == null && s.resolution == ResolutionStatus.RESOLVED) {
                e.add(s.id + ": RESOLVED 인데 target 이 없음");
            }
            if (s.implementationMethodId != null) {
                MethodInfo impl = r.method(s.implementationMethodId);
                MethodInfo decl = s.targetMethodId == null ? null : r.method(s.targetMethodId);
                if (impl == null) {
                    e.add(s.id + ": implementation " + s.implementationMethodId + " 없음");
                } else if (!impl.hasBody) {
                    e.add(s.id + ": implementation " + impl.id + " 에 본문이 없음");
                }
                if (decl == null || decl.hasBody) {
                    e.add(s.id + ": 본문 있는 선언 대상에 구현 연결이 존재");
                }
            }
            if (s.expansion == Expansion.EXPANDED) {
                MethodInfo x = s.expansionTargetId() == null ? null : r.method(s.expansionTargetId());
                if (x == null || !s.id.equals(x.expandedAt)) {
                    e.add(s.id + ": EXPANDED 인데 전개 대상의 expanded-at 불일치");
                }
            }
            if (s.expandedAt != null && r.callSite(s.expandedAt) == null) {
                e.add(s.id + ": expanded-at " + s.expandedAt + " 없음");
            }
            if (s.controlBlockId != null) {
                ControlBlock b = r.block(s.controlBlockId);
                if (b == null) {
                    e.add(s.id + ": control-block " + s.controlBlockId + " 없음");
                } else {
                    if (!b.methodId.equals(s.callerMethodId)) {
                        e.add(s.id + ": 블록 " + b.id + " 의 메서드와 호출자 불일치");
                    }
                    if (!b.callIds.contains(s.id)) {
                        e.add(s.id + ": 블록 " + b.id + " 의 calls 에 누락");
                    }
                }
            }
        }
        for (ControlBlock b : r.blocks) {
            if (r.method(b.methodId) == null) {
                e.add(b.id + ": method " + b.methodId + " 없음");
            }
            for (String cid : b.callIds) {
                CallSiteInfo s = r.callSite(cid);
                if (s == null || !b.id.equals(s.controlBlockId)) {
                    e.add(b.id + ": call " + cid + " 의 control-block-id 불일치");
                }
            }
            if (b.parentBlockId != null) {
                ControlBlock p = r.block(b.parentBlockId);
                if (p == null) {
                    e.add(b.id + ": parent " + b.parentBlockId + " 없음");
                } else {
                    if (!p.methodId.equals(b.methodId)) {
                        e.add(b.id + ": 부모 블록과 메서드 불일치");
                    }
                    if (!p.childBlockIds.contains(b.id)) {
                        e.add(b.id + ": 부모 " + p.id + " 의 children 에 누락");
                    }
                }
            }
            for (String ch : b.childBlockIds) {
                ControlBlock c = r.block(ch);
                if (c == null || !b.id.equals(c.parentBlockId)) {
                    e.add(b.id + ": child " + ch + " 의 parent 불일치");
                }
            }
            Set<String> seen = new HashSet<>();
            for (ControlBlock cur = b; cur != null && cur.parentBlockId != null; cur = r.block(cur.parentBlockId)) {
                if (!seen.add(cur.id)) {
                    e.add(b.id + ": 부모 체인 순환");
                    break;
                }
            }
            if (b.type == BlockType.TRY) {
                for (String cid : b.catchBlockIds) {
                    ControlBlock c = r.block(cid);
                    if (c == null || c.type != BlockType.CATCH || !b.id.equals(c.tryBlockId)) {
                        e.add(b.id + ": catch " + cid + " 연결 오류");
                    }
                }
                if (b.finallyBlockId != null) {
                    ControlBlock f = r.block(b.finallyBlockId);
                    if (f == null || f.type != BlockType.FINALLY || !b.id.equals(f.tryBlockId)) {
                        e.add(b.id + ": finally " + b.finallyBlockId + " 연결 오류");
                    }
                }
            }
            if ((b.type == BlockType.CATCH || b.type == BlockType.FINALLY)
                    && !Objects.equals(b.tryBlockId, b.parentBlockId)) {
                e.add(b.id + ": try-block-id 와 parent-block-id 불일치");
            }
            if (b.caughtBy != null) {
                ControlBlock c = r.block(b.caughtBy);
                if (c == null || c.type != BlockType.CATCH) {
                    e.add(b.id + ": caught-by " + b.caughtBy + " 가 catch 블록이 아님");
                }
            }
            for (String f : b.executesFinally) {
                ControlBlock fb = r.block(f);
                if (fb == null || fb.type != BlockType.FINALLY) {
                    e.add(b.id + ": executes-finally " + f + " 가 finally 블록이 아님");
                }
            }
        }
        for (CallRelation rel : r.relations) {
            if (r.method(rel.fromMethodId) == null || r.method(rel.toMethodId) == null) {
                e.add(rel.id + ": from/to 메서드 없음");
            }
            for (String sid : rel.callSiteIds) {
                CallSiteInfo s = r.callSite(sid);
                if (s == null || !s.callerMethodId.equals(rel.fromMethodId) || !rel.toMethodId.equals(s.targetMethodId)) {
                    e.add(rel.id + ": call-site " + sid + " 불일치");
                }
            }
        }
        for (RequestParameterInfo p : r.parameters) {
            if (r.method(p.sourceMethodId) == null) {
                e.add(p.id + ": source-method " + p.sourceMethodId + " 없음");
            }
            if (p.callSiteId != null && r.callSite(p.callSiteId) == null) {
                e.add(p.id + ": call-site " + p.callSiteId + " 없음");
            }
        }
        for (ObjectInfo o : r.objects) {
            if (o.classId != null && r.clazz(o.classId) == null) {
                e.add(o.id + ": class " + o.classId + " 없음");
            }
            for (String bid : o.bindingIds) {
                Binding b = r.binding(bid);
                if (b == null || !o.id.equals(b.objectId)) {
                    e.add(o.id + ": binding " + bid + " 불일치");
                }
            }
        }
        for (Binding b : r.bindings) {
            if (r.object(b.objectId) == null) {
                e.add(b.id + ": object " + b.objectId + " 없음");
            }
            if (r.method(b.methodId) == null) {
                e.add(b.id + ": method " + b.methodId + " 없음");
            }
            if (b.callSiteId != null && r.callSite(b.callSiteId) == null) {
                e.add(b.id + ": call-site " + b.callSiteId + " 없음");
            }
            if (b.controlBlockId != null && (r.block(b.controlBlockId) == null
                    || !r.block(b.controlBlockId).methodId.equals(b.methodId))) {
                e.add(b.id + ": control-block " + b.controlBlockId + " 불일치");
            }
            for (ValueSource v : b.sources) {
                if (v.parameterId() != null && r.parameter(v.parameterId()) == null) {
                    e.add(b.id + ": request parameter " + v.parameterId() + " 없음");
                }
                v.via().forEach(sid -> {
                    if (r.callSite(sid) == null) {
                        e.add(b.id + ": via " + sid + " 없음");
                    }
                });
            }
        }
        for (VariableInfo v : r.variables) {
            if (r.method(v.methodId) == null) {
                e.add(v.id + ": method " + v.methodId + " 없음");
            }
            if (v.changeIds.isEmpty()) {
                e.add(v.id + ": 변경 이력 없음");
            }
            for (String cid : v.changeIds) {
                VariableChange c = r.variableChange(cid);
                if (c == null || !v.id.equals(c.variableId) || !v.methodId.equals(c.methodId)) {
                    e.add(v.id + ": change " + cid + " 불일치");
                    continue;
                }
                if (c.controlBlockId != null && (r.block(c.controlBlockId) == null
                        || !r.block(c.controlBlockId).methodId.equals(c.methodId))) {
                    e.add(c.id + ": control-block " + c.controlBlockId + " 불일치");
                }
                c.callSiteIds.forEach(sid -> {
                    if (r.callSite(sid) == null || !r.callSite(sid).callerMethodId.equals(c.methodId)) {
                        e.add(c.id + ": call-site " + sid + " 불일치");
                    }
                });
                for (ValueSource s : c.sources) {
                    if (s.parameterId() != null && r.parameter(s.parameterId()) == null) {
                        e.add(c.id + ": request parameter " + s.parameterId() + " 없음");
                    }
                }
            }
        }
        for (RequestSpecEntry q : r.requestSpec) {
            for (String pid : q.parameterIds) {
                RequestParameterInfo p = r.parameter(pid);
                if (p == null || !q.name.equals(p.name)) {
                    e.add(q.id + ": parameter " + pid + " 불일치");
                }
            }
            q.bindingIds.forEach(bid -> {
                if (r.binding(bid) == null) {
                    e.add(q.id + ": binding " + bid + " 없음");
                }
            });
        }
        return e;
    }
}
