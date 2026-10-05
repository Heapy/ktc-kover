package example

class Greeter {
    fun greet(name: String): String {
        if (name.isBlank()) {
            return "Hello, stranger!"
        }
        return "Hello, $name!"
    }

    fun goodbye(): String {
        return "Goodbye!"
    }
}
