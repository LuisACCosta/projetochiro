package com.Luis.chironproject.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.Luis.chironproject.R

// Cores da identidade Chiron
private val ChironRoxoProfundo = Color(0xFF480048)
private val ChironRoxo = Color(0xFF5C008B)
private val ChironLaranja = Color(0xFFFF6200)
private val ChironLaranjaClaro = Color(0xFFF7991C)

@Composable
fun OverlayScreen() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            // Fundo roxo com leve transparência pra cobrir mas não tampar 100%
            .background(ChironRoxoProfundo.copy(alpha = 0.96f)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .padding(32.dp)
                .background(
                    color = ChironRoxo,
                    shape = RoundedCornerShape(28.dp)
                )
                .padding(horizontal = 36.dp, vertical = 44.dp)
        ) {
            // Logo do escudo Chiron no lugar do emoji
            Image(
                painter = painterResource(id = R.drawable.logo_escudo_chiron_laranja),
                contentDescription = "Chiron",
                modifier = Modifier.size(120.dp)
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "Conteúdo inadequado",
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                color = ChironLaranja,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = "Passe para o próximo vídeo",
                fontSize = 16.sp,
                color = ChironLaranjaClaro,
                textAlign = TextAlign.Center
            )
        }
    }
}