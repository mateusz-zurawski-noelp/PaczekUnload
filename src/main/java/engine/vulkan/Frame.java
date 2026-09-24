package engine.vulkan;

import java.nio.LongBuffer;

import static org.lwjgl.system.MemoryStack.stackGet;

/**
 * Vulkan renderuje asynchronicznie - CPU wysyła polecenia do GPU i leci dalej,
 * nie czekając aż się wykonają. Żeby nie nadpisać danych klatki, która wciąż
 * jest rysowana, potrzeba obiektów synchronizacji "na klatkę w locie":
 *
 *  - imageAvailableSemaphore - sygnalizuje, że swapchain dał nam obraz do rysowania
 *  - renderFinishedSemaphore - sygnalizuje, że GPU skończył rysować, można prezentować
 *  - fence                   - pozwala CPU poczekać, aż GPU skończy tę klatkę,
 *                              zanim zacznie nagrywać kolejną
 */
public final class Frame {

    private final long imageAvailableSemaphore;
    private final long renderFinishedSemaphore;
    private final long fence;

    public Frame(long imageAvailableSemaphore, long renderFinishedSemaphore, long fence) {
        this.imageAvailableSemaphore = imageAvailableSemaphore;
        this.renderFinishedSemaphore = renderFinishedSemaphore;
        this.fence = fence;
    }

    public long imageAvailableSemaphore() {
        return imageAvailableSemaphore;
    }

    public LongBuffer pImageAvailableSemaphore() {
        return stackGet().longs(imageAvailableSemaphore);
    }

    public long renderFinishedSemaphore() {
        return renderFinishedSemaphore;
    }

    public LongBuffer pRenderFinishedSemaphore() {
        return stackGet().longs(renderFinishedSemaphore);
    }

    public long fence() {
        return fence;
    }

    public LongBuffer pFence() {
        return stackGet().longs(fence);
    }
}
