package com.example.access.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.android.billingclient.api.ProductDetails

import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
/**
 * A dialog that presents the Pro subscription offer and allows the user to purchase.
 * Caller should provide product details (price, name) and handle purchase flow.
 */
@Composable
fun PaywallDialog(
    onDismissRequest: () -> Unit,
    onPurchaseClick: () -> Unit,
    productDetails: ProductDetails?,
    isLoading: Boolean,
    errorMessage: String? = null
) {
    Dialog(onDismissRequest = onDismissRequest) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 6.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Icon(
                    Icons.Filled.Star,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    "Upgrade to Pro",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Black,
                    textAlign = TextAlign.Center
                )
                Text(
                    "Unlock unlimited members, custom branding, and advanced features.",
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                // Price card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            productDetails?.name ?: "Pro Subscription",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            productDetails?.subscriptionOfferDetails?.firstOrNull()?.pricingPhases
                                ?.pricingPhaseList?.firstOrNull()?.formattedPrice
                                ?: "$4.99/month",
                            style = MaterialTheme.typography.displaySmall,
                            fontWeight = FontWeight.Black,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            productDetails?.description ?: "Monthly subscription, cancel anytime.",
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                
                errorMessage?.let { message ->
                    Text(
                        message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(8.dp))
                }
                
                if (isLoading) {
                    CircularProgressIndicator()
                } else {
                    Button(
                        onClick = onPurchaseClick,
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.large,
                        enabled = productDetails != null && errorMessage == null
                    ) {
                        Text("Subscribe Now", style = MaterialTheme.typography.titleMedium)
                    }
                }
                TextButton(
                    onClick = onDismissRequest,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Maybe Later")
                }
                Text(
                    "Your subscription will sync across all devices in your organization.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Preview
@Composable
private fun PaywallDialogPreview() {
    MaterialTheme {
        PaywallDialog(
            onDismissRequest = {},
            onPurchaseClick = {},
            productDetails = null,
            isLoading = false,
            errorMessage = null
        )
    }
}