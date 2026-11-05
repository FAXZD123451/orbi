package com.orbi.app.vision

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions

/**
 * El entorno: qué cosas hay frente a la cámara (taza, teclado, planta, persona...).
 * Usa ML Kit Image Labeling con su modelo incluido: funciona en el celular, sin internet.
 *
 * No analiza todo el tiempo: solo cuando le preguntas "¿qué ves?" o con el modo creador abierto.
 */
object SceneSense {
    private val labeler by lazy {
        ImageLabeling.getClient(ImageLabelerOptions.Builder().setConfidenceThreshold(0.55f).build())
    }
    private val main = Handler(Looper.getMainLooper())
    private val waiting = ArrayList<(List<Pair<String, Float>>) -> Unit>()

    /** Últimas etiquetas vistas (en español cuando hay traducción), para el modo creador. */
    @Volatile var lastLabels: List<Pair<String, Float>> = emptyList()
        private set

    /** ¿Alguien espera una respuesta? Así la cámara sabe que debe mirar el entorno en el próximo cuadro. */
    val pending: Boolean get() = synchronized(waiting) { waiting.isNotEmpty() }

    /** Pide las etiquetas del próximo cuadro. [callback] llega en el hilo principal. */
    fun request(callback: (List<Pair<String, Float>>) -> Unit) {
        synchronized(waiting) { waiting.add(callback) }
    }

    /** Se llama desde el hilo de la cámara con un cuadro ya enderezado. */
    fun analyze(bitmap: Bitmap) {
        labeler.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { labels ->
                val list = labels.sortedByDescending { it.confidence }.take(8).map { spanish(it.text) to it.confidence }
                lastLabels = list
                deliver(list)
            }
            .addOnFailureListener { deliver(emptyList()) }
    }

    private fun deliver(list: List<Pair<String, Float>>) {
        val callbacks = synchronized(waiting) { waiting.toList().also { waiting.clear() } }
        if (callbacks.isNotEmpty()) main.post { callbacks.forEach { it(list) } }
    }

    /** Traducción de las etiquetas más comunes de ML Kit (vienen en inglés). */
    fun spanish(label: String) = ES[label] ?: label

    private val ES = mapOf(
        "Person" to "persona", "Hand" to "mano", "Smile" to "sonrisa", "Beard" to "barba", "Hair" to "cabello",
        "Glasses" to "lentes", "Sunglasses" to "lentes de sol", "Eyelash" to "pestañas", "Selfie" to "selfie",
        "Cup" to "taza", "Mug" to "taza", "Coffee" to "café", "Bottle" to "botella", "Food" to "comida",
        "Fruit" to "fruta", "Cake" to "pastel", "Bread" to "pan", "Pizza" to "pizza", "Drink" to "bebida",
        "Computer" to "computadora", "Laptop" to "laptop", "Keyboard" to "teclado", "Mouse" to "mouse",
        "Monitor" to "monitor", "Television" to "televisor", "Mobile phone" to "celular", "Screenshot" to "pantalla",
        "Headphones" to "audífonos", "Speaker" to "parlante", "Camera" to "cámara", "Musical instrument" to "instrumento",
        "Guitar" to "guitarra", "Piano" to "piano",
        "Table" to "mesa", "Desk" to "escritorio", "Chair" to "silla", "Couch" to "sofá", "Bed" to "cama",
        "Pillow" to "almohada", "Cushion" to "cojín", "Shelf" to "estante", "Room" to "habitación",
        "Wall" to "pared", "Window" to "ventana", "Curtain" to "cortina", "Door" to "puerta", "Lamp" to "lámpara",
        "Ceiling" to "techo", "Floor" to "piso", "Bathroom" to "baño", "Kitchen" to "cocina", "Building" to "edificio",
        "Plant" to "planta", "Flower" to "flor", "Tree" to "árbol", "Grass" to "pasto", "Sky" to "cielo",
        "Cloud" to "nube", "Sunset" to "atardecer", "Night" to "noche", "Darkness" to "oscuridad",
        "Dog" to "perro", "Cat" to "gato", "Bird" to "pájaro", "Pet" to "mascota",
        "Car" to "auto", "Vehicle" to "vehículo", "Wheel" to "rueda", "Tire" to "llanta", "Bicycle" to "bicicleta",
        "Motorcycle" to "moto", "Road" to "calle",
        "Book" to "libro", "Paper" to "papel", "Pen" to "bolígrafo", "Poster" to "póster", "Toy" to "juguete",
        "Bag" to "bolso", "Clock" to "reloj", "Watch" to "reloj", "Shoe" to "zapato", "Jacket" to "chaqueta",
        "Jeans" to "jeans", "Hat" to "gorro", "Cap" to "gorra", "Sleeve" to "manga", "Shorts" to "shorts",
        "Tattoo" to "tatuaje", "Fun" to "diversión", "Event" to "evento", "Text" to "texto", "Pattern" to "patrón",
    )
}
