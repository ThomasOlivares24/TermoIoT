
package com.example.termoiot
import androidx.compose.material3.OutlinedTextField
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.termoiot.ui.theme.TermoIoTTheme
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener

class MainActivity : ComponentActivity() {
    private val requireReauthentication = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            TermoIoTTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    TermoIoTApp(
                        requireReauth = requireReauthentication.value,
                        onReauthComplete = {
                            requireReauthentication.value = false
                        }
                    )
                }
            }
        }
    }
    override fun onStop() {
        super.onStop()

        getSharedPreferences("termoiot_security", MODE_PRIVATE)
            .edit()
            .putLong("backgrounded_at", System.currentTimeMillis())
            .apply()
    }

    override fun onStart() {
        super.onStart()

        val prefs = getSharedPreferences(
            "termoiot_security",
            MODE_PRIVATE
        )

        val backgroundedAt = prefs.getLong("backgrounded_at", 0L)

        if (backgroundedAt > 0L) {
            val elapsed = System.currentTimeMillis() - backgroundedAt

            if (elapsed >= 5 * 60 * 1000L &&
                FirebaseAuth.getInstance().currentUser != null
            ) {
                requireReauthentication.value = true
            }

            prefs.edit().remove("backgrounded_at").apply()
        }
    }
}


@Composable
fun TermoIoTApp(
    requireReauth: Boolean,
    onReauthComplete: () -> Unit
) {
    val auth = remember { FirebaseAuth.getInstance() }

    var userEmail by remember {
        mutableStateOf(
            if (requireReauth) null else auth.currentUser?.email
        )
    }

    LaunchedEffect(requireReauth) {
        if (requireReauth) {
            userEmail = null
        }
    }

    if (userEmail == null) {
        AuthScreen(
            onLogin = {
                userEmail = auth.currentUser?.email
                onReauthComplete()
            }
        )
    } else {
        MonitorScreen(
            email = userEmail ?: "",
            onLogout = {
                auth.signOut()
                userEmail = null
                onReauthComplete()
            }
        )
    }
}

@Composable
fun AuthScreen(onLogin: () -> Unit) {
    val auth = remember { FirebaseAuth.getInstance() }

    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var registering by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "TermoIoT",
            style = MaterialTheme.typography.headlineLarge
        )
        Text(
            if (registering) "Crear cuenta" else "Iniciar sesión"
        )

        Spacer(Modifier.height(20.dp))

        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text("Correo electrónico") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Contraseña") },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        Spacer(Modifier.height(16.dp))

        Button(
            enabled = !busy && email.isNotBlank() && password.isNotBlank(),
            onClick = {
                busy = true
                message = "Conectando..."

                val task = if (registering) {
                    auth.createUserWithEmailAndPassword(
                        email.trim(), password
                    )
                } else {
                    auth.signInWithEmailAndPassword(
                        email.trim(), password
                    )
                }

                task.addOnCompleteListener { result ->
                    busy = false
                    if (result.isSuccessful) {
                        onLogin()
                    } else {
                        message = result.exception?.localizedMessage
                            ?: "No se pudo completar la operación."
                    }
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (busy) "Espera..." else if (registering) {
                "Registrarme"
            } else {
                "Entrar"
            })
        }

        TextButton(onClick = {
            registering = !registering
            message = ""
        }) {
            Text(
                if (registering) "Ya tengo una cuenta"
                else "Crear una cuenta"
            )
        }

        if (message.isNotBlank()) {
            Text(message)
        }
    }
}


@Composable
fun MonitorScreen(email: String, onLogout: () -> Unit) {
    // Cada cuenta utiliza su propio UID como nodo en Realtime Database.
    val uid = remember { FirebaseAuth.getInstance().currentUser?.uid.orEmpty() }

    val database = remember(uid) {
        FirebaseDatabase.getInstance(
            "https://termoiot-53a3d-default-rtdb.firebaseio.com"
        ).getReference("usuarios").child(uid)
    }

    var temperature by remember { mutableIntStateOf(22) }
    var minimum by remember { mutableIntStateOf(18) }
    var maximum by remember { mutableIntStateOf(28) }

    var minimumInput by remember { mutableStateOf("18") }
    var maximumInput by remember { mutableStateOf("28") }
    var editingThresholds by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("Conectando con Firebase...") }

    DisposableEffect(database) {
        var initializationRequested = false

        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!snapshot.exists()) {
                    if (!initializationRequested) {
                        initializationRequested = true
                        // Actualizar campos individuales permite aplicar las reglas
                        // de escritura y validación definidas para cada campo.
                        database.updateChildren(
                            mapOf(
                                "temperatura" to 22,
                                "temperaturaMinima" to 18,
                                "temperaturaMaxima" to 28
                            )
                        ).addOnCompleteListener { result ->
                            initializationRequested = false
                            message = if (result.isSuccessful) {
                                "Termómetro inicializado para esta cuenta"
                            } else {
                                "No se pudo inicializar: ${
                                    result.exception?.localizedMessage ?: "Error desconocido"
                                }"
                            }
                        }
                    }
                    return
                }

                temperature = snapshot.child("temperatura")
                    .getValue(Int::class.java) ?: 22

                minimum = snapshot.child("temperaturaMinima")
                    .getValue(Int::class.java) ?: 18

                maximum = snapshot.child("temperaturaMaxima")
                    .getValue(Int::class.java) ?: 28

                if (!editingThresholds) {
                    minimumInput = minimum.toString()
                    maximumInput = maximum.toString()
                }

                message = "Conectado a Firebase"
            }

            override fun onCancelled(error: DatabaseError) {
                message = "Error de base de datos: ${error.message}"
            }
        }

        database.addValueEventListener(listener)

        onDispose {
            database.removeEventListener(listener)
        }
    }

    val lightColor = when {
        temperature < minimum -> Color.Blue
        temperature > maximum -> Color.Red
        else -> Color.Gray
    }

    val status = when {
        temperature < minimum -> "Temperatura baja"
        temperature > maximum -> "Temperatura alta"
        else -> "Temperatura normal"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            "TermoIoT",
            style = MaterialTheme.typography.headlineLarge
        )

        Spacer(Modifier.height(8.dp))
        Text("Sesión: $email")

        Spacer(Modifier.height(20.dp))
        Text("Temperatura actual")
        Text(
            "$temperature °C",
            style = MaterialTheme.typography.displayMedium
        )

        Spacer(Modifier.height(16.dp))

        Box(
            modifier = Modifier
                .size(48.dp)
                .background(lightColor, CircleShape)
        )

        Spacer(Modifier.height(8.dp))
        Text(status)

        Spacer(Modifier.height(24.dp))

        Text(
            "Configurar temperaturas",
            style = MaterialTheme.typography.titleLarge
        )

        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = minimumInput,
            onValueChange = {
                minimumInput = it
                editingThresholds = true
            },
            label = { Text("Temperatura mínima (°C)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            value = maximumInput,
            onValueChange = {
                maximumInput = it
                editingThresholds = true
            },
            label = { Text("Temperatura máxima (°C)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(12.dp))

        Button(
            onClick = {
                val minValue = minimumInput.toIntOrNull()
                val maxValue = maximumInput.toIntOrNull()

                when {
                    minValue == null || maxValue == null -> {
                        message = "Introduce temperaturas enteras válidas."
                    }

                    minValue !in -40..125 || maxValue !in -40..125 -> {
                        message = "Los límites deben estar entre -40 y 125 °C."
                    }

                    minValue >= maxValue -> {
                        message =
                            "El mínimo debe ser menor que el máximo."
                    }

                    else -> {
                        message = "Guardando configuración..."

                        database.updateChildren(
                            mapOf(
                                "temperaturaMinima" to minValue,
                                "temperaturaMaxima" to maxValue
                            )
                        ).addOnSuccessListener {
                            minimum = minValue
                            maximum = maxValue
                            editingThresholds = false
                            message = "Configuración guardada."
                        }.addOnFailureListener { error ->
                            message =
                                "No se pudo guardar: ${error.localizedMessage}"
                        }
                    }
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Guardar configuración")
        }

        Spacer(Modifier.height(20.dp))

        Text(
            "Simulación del sensor",
            style = MaterialTheme.typography.titleMedium
        )

        Spacer(Modifier.height(8.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(onClick = {
                val newTemperature = temperature - 1
                if (newTemperature !in -40..125) {
                    message = "La temperatura debe estar entre -40 y 125 °C."
                } else {
                    database.child("temperatura")
                        .setValue(newTemperature)
                        .addOnFailureListener { error ->
                            message = "Error: ${error.localizedMessage}"
                        }
                }
            }) {
                Text("− 1 °C")
            }

            Button(onClick = {
                val newTemperature = temperature + 1
                if (newTemperature !in -40..125) {
                    message = "La temperatura debe estar entre -40 y 125 °C."
                } else {
                    database.child("temperatura")
                        .setValue(newTemperature)
                        .addOnFailureListener { error ->
                            message = "Error: ${error.localizedMessage}"
                        }
                }
            }) {
                Text("+ 1 °C")
            }
        }

        Spacer(Modifier.height(16.dp))
        Text(message, style = MaterialTheme.typography.bodySmall)

        Spacer(Modifier.height(8.dp))

        OutlinedButton(onClick = onLogout) {
            Text("Cerrar sesión")
        }
    }
}
