package example

import kotlin.test.Test
import kotlin.test.assertEquals

class GreeterTest {
    @Test
    fun greetsByName() {
        assertEquals("Hello, Kotlin!", Greeter().greet("Kotlin"))
    }
}
