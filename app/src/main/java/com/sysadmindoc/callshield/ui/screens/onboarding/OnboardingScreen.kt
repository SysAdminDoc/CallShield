package com.sysadmindoc.callshield.ui.screens.onboarding

import android.app.role.RoleManager
import android.content.Context
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.sysadmindoc.callshield.ui.theme.*

data class OnboardingPage(
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val title: String,
    val subtitle: String,
    val color: androidx.compose.ui.graphics.Color
)

@Composable
fun OnboardingScreen(onComplete: () -> Unit) {
    val context = LocalContext.current
    var currentPage by remember { mutableIntStateOf(0) }

    val pages = listOf(
        OnboardingPage(Icons.Default.Shield, "Welcome to CallShield", "Open-source spam call & text blocker with 11-layer detection. No API keys, no tracking.", CatGreen),
        OnboardingPage(Icons.Default.Security, "Powerful Detection", "Database matching, heuristic analysis, SMS content scanning, STIR/SHAKEN verification, and more.", CatBlue),
        OnboardingPage(Icons.Default.PhoneCallback, "Set as Call Screener", "CallShield needs to be your default call screening app to block spam calls.", CatMauve),
        OnboardingPage(Icons.Default.Sync, "Stay Updated", "Spam database syncs automatically from GitHub every 6 hours. Community-driven and always growing.", CatPeach),
    )

    val roleManager = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) context.getSystemService(Context.ROLE_SERVICE) as? RoleManager else null
    }
    val screeningLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {}

    Column(
        modifier = Modifier.fillMaxSize().background(Black).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.weight(1f))

        // Page content
        AnimatedContent(targetState = currentPage, transitionSpec = {
            slideInHorizontally { it } + fadeIn() togetherWith slideOutHorizontally { -it } + fadeOut()
        }, label = "onboarding") { page ->
            val p = pages[page]
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(p.icon, null, tint = p.color, modifier = Modifier.size(96.dp))
                Spacer(Modifier.height(24.dp))
                Text(p.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = p.color, textAlign = TextAlign.Center)
                Spacer(Modifier.height(12.dp))
                Text(p.subtitle, style = MaterialTheme.typography.bodyLarge, color = CatSubtext, textAlign = TextAlign.Center)

                // Call screener button on page 3
                if (page == 2) {
                    Spacer(Modifier.height(24.dp))
                    Button(
                        onClick = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && roleManager != null) {
                                screeningLauncher.launch(roleManager.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING))
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = CatMauve),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.PhoneCallback, null, tint = Black)
                        Spacer(Modifier.width(8.dp))
                        Text("Set as Call Screener", color = Black, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        Spacer(Modifier.weight(1f))

        // Page indicators
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            pages.forEachIndexed { i, p ->
                Box(
                    modifier = Modifier
                        .size(if (i == currentPage) 10.dp else 8.dp)
                        .clip(CircleShape)
                        .background(if (i == currentPage) p.color else CatOverlay)
                )
            }
        }

        Spacer(Modifier.height(32.dp))

        // Navigation
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            if (currentPage > 0) {
                TextButton(onClick = { currentPage-- }) {
                    Text("Back", color = CatSubtext)
                }
            } else {
                Spacer(Modifier.width(1.dp))
            }

            Button(
                onClick = {
                    if (currentPage < pages.lastIndex) currentPage++
                    else onComplete()
                },
                colors = ButtonDefaults.buttonColors(containerColor = pages[currentPage].color),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    if (currentPage < pages.lastIndex) "Next" else "Get Started",
                    color = Black, fontWeight = FontWeight.Bold
                )
                if (currentPage < pages.lastIndex) {
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.Default.ArrowForward, null, tint = Black, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}
