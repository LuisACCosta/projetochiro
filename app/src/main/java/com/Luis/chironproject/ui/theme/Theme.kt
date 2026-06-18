package com.Luis.chironproject.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

// Esquema único da identidade Chiron (versão primária):
// fundo ROXO, textos em LARANJA, botão LARANJA com texto ROXO dentro.
// Usado tanto no modo claro quanto no escuro pra manter a identidade sempre igual.
private val ChironColorScheme = lightColorScheme(
    primary = ChironLaranja,            // cor do botão (caixa laranja)
    onPrimary = ChironRoxoProfundo,     // texto DENTRO do botão (roxo)
    secondary = ChironLaranjaClaro,
    onSecondary = ChironRoxoProfundo,
    tertiary = ChironMagenta,
    background = ChironRoxoProfundo,     // fundo geral (roxo)
    onBackground = ChironLaranja,        // texto sobre o fundo (laranja)
    surface = ChironRoxoProfundo,        // superfícies (mesmo roxo do fundo)
    onSurface = ChironLaranja            // texto sobre superfícies (laranja)
)

@Composable
fun ChironProjectTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // dynamicColor DESLIGADO de propósito: com ele ligado, o Android usaria
    // as cores do papel de parede do usuário e ignoraria a identidade Chiron.
    content: @Composable () -> Unit
) {
    // Mesmo esquema sempre, independente de claro/escuro.
    MaterialTheme(
        colorScheme = ChironColorScheme,
        typography = Typography,
        content = content
    )
}