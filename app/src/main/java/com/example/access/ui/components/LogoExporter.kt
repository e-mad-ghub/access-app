package com.example.access.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.easyapps.easypass.R

@Preview(showBackground = false)
@Composable
fun ExportEasyPassLogo() {
    Box(
        modifier = Modifier.size(512.dp),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(id = R.drawable.ic_pass_logo),
            contentDescription = null,
            modifier = Modifier.size(512.dp)
        )
    }
}
