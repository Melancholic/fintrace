package com.github.melancholic.fintrace.core.config


// API Constants
const val API_V1_BASE_PATH = "/api/v1"
const val WORKSPACES_AREA_API_PATH = API_V1_BASE_PATH + "/workspaces"
const val WORKSPACE_AREA_API_PATH = WORKSPACES_AREA_API_PATH + "/{workspaceId}"
const val OPERATIONS_AREA_API_PATH = WORKSPACE_AREA_API_PATH + "/operations"
const val ACCOUNTS_AREA_API_PATH = WORKSPACE_AREA_API_PATH + "/accounts"
const val CATEGORIES_AREA_API_PATH = WORKSPACE_AREA_API_PATH + "/categories"

const val ADMIN_API_V1_BASE_PATH = "admin/api/v1"

// Business constants
const val ROOT_INCOME_CAT_NAME = "Income"
const val ROOT_EXPENSE_CAT_NAME = "Expense"
const val ROOT_OTHERS_CAT_NAME = "Others"
