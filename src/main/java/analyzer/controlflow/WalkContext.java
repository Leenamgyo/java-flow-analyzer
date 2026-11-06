package analyzer.controlflow;

import analyzer.model.ControlBlock;

import java.util.ArrayList;
import java.util.List;

/**
 * 메서드 본문 순회 중의 위치 정보 (불변).
 *
 * @param block       현재 소속 제어 블록 (메서드 최상위면 null)
 * @param position    블록 안의 위치 (BODY, CONDITION, ...)
 * @param guards      이 위치에 도달하기 위한 조건 설명 (바깥→안쪽)
 * @param conditional 조건부 실행 여부
 * @param deferred    람다 본문처럼 정의 시점에 실행되지 않는 위치
 * @param frames      감싸고 있는 try 문 (바깥→안쪽)
 */
public record WalkContext(ControlBlock block, String position, List<String> guards, boolean conditional,
                          boolean deferred, List<TryFrame> frames) {

    public enum Region { TRY_BODY, CATCH, FINALLY }

    /** try 문 1개와, 현재 위치가 그 try 문의 어느 영역인지 */
    public record TryFrame(ControlBlock tryBlock, boolean hasFinally, Region region, ControlBlock catchBlock) {
    }

    public static WalkContext root() {
        return new WalkContext(null, "BODY", List.of(), false, false, List.of());
    }

    public WalkContext at(ControlBlock b, String pos) {
        return new WalkContext(b, pos, guards, conditional, deferred, frames);
    }

    public WalkContext position(String pos) {
        return new WalkContext(block, pos, guards, conditional, deferred, frames);
    }

    public WalkContext conditionalWith(String guardOrNull) {
        List<String> g = guards;
        if (guardOrNull != null) {
            g = new ArrayList<>(guards);
            g.add(guardOrNull);
        }
        return new WalkContext(block, position, List.copyOf(g), true, deferred, frames);
    }

    public WalkContext lambda() {
        return new WalkContext(block, position, guards, conditional, true, List.of());
    }

    public WalkContext push(TryFrame f) {
        List<TryFrame> fs = new ArrayList<>(frames);
        fs.add(f);
        return new WalkContext(block, position, guards, conditional, deferred, List.copyOf(fs));
    }
}
