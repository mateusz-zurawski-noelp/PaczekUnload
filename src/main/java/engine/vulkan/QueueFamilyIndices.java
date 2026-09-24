package engine.vulkan;

import java.util.stream.IntStream;

/**
 * Karta graficzna udostępnia różne "rodziny kolejek" (queue families) - grupy
 * kolejek wspierające różne zestawy operacji (grafika, compute, transfer,
 * prezentacja na ekran...). Do narysowania czegokolwiek na ekranie potrzeba
 * co najmniej jednej rodziny z obsługą grafiki i jednej z obsługą prezentacji
 * (na wielu GPU to ta sama rodzina, ale nie jest to gwarantowane).
 */
final class QueueFamilyIndices {

    Integer graphicsFamily;
    Integer presentFamily;

    boolean isComplete() {
        return graphicsFamily != null && presentFamily != null;
    }

    int[] unique() {
        return IntStream.of(graphicsFamily, presentFamily).distinct().toArray();
    }
}
