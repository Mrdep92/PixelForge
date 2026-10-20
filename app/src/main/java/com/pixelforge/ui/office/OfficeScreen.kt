package com.pixelforge.ui.office

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pixelforge.ui.MainViewModel
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pixelforge.domain.model.Agent
import com.pixelforge.domain.model.AgentRole
import com.pixelforge.domain.model.AgentStatus

@Composable
fun OfficeScreen(vm: MainViewModel) {
    val agents by vm.agents.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF1A1A2E))
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF16213E))
                .padding(12.dp)
        ) {
            Text("PixelForge Office", color = Color(0xFF00E5FF), fontWeight = FontWeight.Bold, fontSize = 18.sp)
        }

        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(8.dp)
        ) {
            val w = maxWidth
            val h = maxHeight
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawRect(Color(0xFF0F3460), size = size)
                // Meja di bawah tiap agent
                agents.forEach { a ->
                    drawRect(
                        color = Color(0xFF533483),
                        topLeft = Offset(size.width * a.positionX - 12.dp.toPx(), size.height * a.positionY + 34.dp.toPx()),
                        size = Size(64.dp.toPx(), 22.dp.toPx())
                    )
                }
            }
            agents.forEach { agent ->
                key(agent.id) {
                    val x by animateDpAsState(w * agent.positionX, tween(1200), label = "x")
                    val y by animateDpAsState(h * agent.positionY, tween(1200), label = "y")
                    AgentSprite(agent = agent, modifier = Modifier.offset(x, y))
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF16213E))
                .horizontalScroll(rememberScrollState())
                .padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            agents.forEach { a ->
                Text("${a.name}: ${a.status.name.take(4)}", color = Color.White, fontSize = 10.sp)
            }
        }
    }
}

@Composable
fun AgentSprite(
    agent: Agent,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Bubble
        if (!agent.bubbleText.isNullOrBlank()) {
            Box(
                modifier = Modifier
                    .background(Color.White, RoundedCornerShape(8.dp))
                    .padding(horizontal = 6.dp, vertical = 3.dp)
            ) {
                Text(
                    text = agent.bubbleText,
                    color = Color.Black,
                    fontSize = 10.sp
                )
            }
            Spacer(modifier = Modifier.height(2.dp))
        }

        // Simple pixel-like body
        Box(
            modifier = Modifier
                .size(28.dp)
                .background(
                    when (agent.role) {
                        AgentRole.ARCHITECT -> Color(0xFF7C4DFF)
                        AgentRole.CODER -> Color(0xFF00E676)
                        AgentRole.AUDITOR -> Color(0xFFFFAB40)
                        AgentRole.DEBUGGER -> Color(0xFFFF5252)
                        AgentRole.BUSINESS -> Color(0xFFFFEB3B)
                        AgentRole.CREATIVE -> Color(0xFF40C4FF)
                        else -> Color(0xFFB0BEC5)
                    },
                    RoundedCornerShape(4.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = agent.name.take(1),
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp
            )
        }

        Text(
            text = agent.name,
            color = Color.White,
            fontSize = 9.sp
        )
    }
}
