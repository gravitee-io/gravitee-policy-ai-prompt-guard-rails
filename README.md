
<!-- GENERATED CODE - DO NOT ALTER THIS OR THE FOLLOWING LINES -->
# AI - Prompt Guard Rails

[![Gravitee.io](https://img.shields.io/static/v1?label=Available%20at&message=Gravitee.io&color=1EC9D2)](https://download.gravitee.io/#graviteeio-apim/plugins/policies/gravitee-policy-ai-prompt-guard-rails/)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://github.com/gravitee-io/gravitee-policy-ai-prompt-guard-rails/blob/master/LICENSE.txt)
[![Releases](https://img.shields.io/badge/semantic--release-conventional%20commits-e10079?logo=semantic-release)](https://github.com/gravitee-io/gravitee-policy-ai-prompt-guard-rails/releases)
[![CircleCI](https://circleci.com/gh/gravitee-io/gravitee-policy-ai-prompt-guard-rails.svg?style=svg)](https://circleci.com/gh/gravitee-io/gravitee-policy-ai-prompt-guard-rails)

## Overview
This policy uses an AI-powered text classification model to evaluate user prompts for potentially inappropriate or malicious content. It can detect a wide range of violations, such as profanity, sexually explicit language, harmful intent, and jailbreak prompt injections, which are adversarial inputs crafted to bypass AI safety mechanisms.

Depending on configuration, when a prompt is flagged:

* **Blocked and flagged** – the request is denied at the gateway
* **Allowed but flagged** – the request proceeds but is logged for monitoring

>**_NOTE_**: You may face an error when using this policy using the Gravitee's docker image. This is due to the fact that the default image are based on Alpine Linux, which does not support the ONNX Runtime. To resolve this issue you need to use the Gravitee's docker image based on Debian, which is available at `graviteeio/apim-gateway:4.8.0-debian`.

## Content Checks

The Content Checks property specifies the classification labels that are applied to evaluate prompts. You should choose Labels in alignment with the selected model's capabilities and the intended filtering goals. For example, filtering for profanity while omitting toxicity checks.

Supported labels are documented in the model’s card or configuration file.


## AI Model Resource

The policy requires an **AI Model Text Classification Resource** to be defined at the API level. This resource serves as the classification engine for evaluating prompts' content during policy execution.

For more information about creating and managing resources, go to [Resources](https://documentation.gravitee.io/apim/policies/resources)

After the resource is created, the policy must be configured with the corresponding name using the **AI Model Resource Name** property.

>**_NOTE_**: The policy will load the model while handling the first request made to the API. Therefore, this first call will take longer than usual, as it includes the model loading time. Subsequent requests will be processed faster.


## Notice

This plugin allows usage of models based on meta LLama4:

* [gravitee-io/Llama-Prompt-Guard-2-22M-onxx](https://huggingface.co/gravitee-io/Llama-Prompt-Guard-2-22M-onnx)
* [gravitee-io/Llama-Prompt-Guard-2-86M-onxx](https://huggingface.co/gravitee-io/Llama-Prompt-Guard-2-86M-onnx)

> Llama 4 is licensed under the Llama 4 Community License, Copyright © Meta Platforms, Inc. All Rights Reserved.




## Phases
The `ai-prompt-guard-rails` policy can be applied to the following API types and flow phases.

### Compatible API types

* `PROXY`

### Supported flow phases:

* Request

## Compatibility matrix
Strikethrough text indicates that a version is deprecated.

| Plugin version| APIM| Java version |
| --- | --- | ---  |
|1.0.0 and after|4.8.x and after|21 |


## Configuration options


#### 
| Name <br>`json name`  | Type <br>`constraint`  | Mandatory  | Default  | Description  |
|:----------------------|:-----------------------|:----------:|:---------|:-------------|
| Content Checks<br>`contentChecks`| string|  | | Comma-separated list of model labels (e.g., TOXIC,OBSCENE)|
| Prompt Location<br>`promptLocation`| string|  | | Prompt Location|
| Request Policy<br>`requestPolicy`| enum (string)|  | `LOG_REQUEST`| Request Policy<br>Values: `BLOCK_REQUEST` `LOG_REQUEST`|
| Resource Name<br>`resourceName`| string|  | | The resource name loading the Text Classification model|
| Sensitivity threshold<br>`sensitivityThreshold`| number|  | `0.5`| |




## Examples

*Only log the request when inappropriate prompt detected*
```json
{
  "api": {
    "definitionVersion": "V4",
    "type": "PROXY",
    "name": "AI - Prompt Guard Rails example API",
    "resources": [
      {
        "name": "ai-model-text-classification-resource",
        "type": "ai-model-text-classification",
        "configuration": "{\"model\":{\"type\":\"MINILMV2_TOXIC_JIGSAW_MODEL\"}}",
        "enabled": true
      }
    ],
    "flows": [
      {
        "name": "Common Flow",
        "enabled": true,
        "selectors": [
          {
            "type": "HTTP",
            "path": "/",
            "pathOperator": "STARTS_WITH"
          }
        ],
        "request": [
          {
            "name": "AI - Prompt Guard Rails",
            "enabled": true,
            "policy": "ai-prompt-guard-rails",
            "configuration":
              {
                  "resourceName": "ai-model-text-classification-resource",
                  "promptLocation": "{#request.jsonContent.prompt}",
                  "contentChecks": "identity_hate,insult,obscene,severe_toxic,threat,toxic",
                  "requestPolicy": "LOG_REQUEST"
              }
          }
        ]
      }
    ]
  }
}

```
*Block request when inappropriate prompt detected*
```json
{
  "api": {
    "definitionVersion": "V4",
    "type": "PROXY",
    "name": "AI - Prompt Guard Rails example API",
    "resources": [
      {
        "name": "ai-model-text-classification-resource",
        "type": "ai-model-text-classification",
        "configuration": "{\"model\":{\"type\":\"MINILMV2_TOXIC_JIGSAW_MODEL\"}}",
        "enabled": true
      }
    ],
    "flows": [
      {
        "name": "Common Flow",
        "enabled": true,
        "selectors": [
          {
            "type": "HTTP",
            "path": "/",
            "pathOperator": "STARTS_WITH"
          }
        ],
        "request": [
          {
            "name": "AI - Prompt Guard Rails",
            "enabled": true,
            "policy": "ai-prompt-guard-rails",
            "configuration":
              {
                  "resourceName": "ai-model-text-classification-resource",
                  "promptLocation": "{#request.jsonContent.prompt}",
                  "contentChecks": "identity_hate,insult,obscene,severe_toxic,threat,toxic",
                  "requestPolicy": "BLOCK_REQUEST"
              }
          }
        ]
      }
    ]
  }
}

```
*Provide a custom sensitivity threshold for inappropriate prompts*
```json
{
  "api": {
    "definitionVersion": "V4",
    "type": "PROXY",
    "name": "AI - Prompt Guard Rails example API",
    "resources": [
      {
        "name": "ai-model-text-classification-resource",
        "type": "ai-model-text-classification",
        "configuration": "{\"model\":{\"type\":\"MINILMV2_TOXIC_JIGSAW_MODEL\"}}",
        "enabled": true
      }
    ],
    "flows": [
      {
        "name": "Common Flow",
        "enabled": true,
        "selectors": [
          {
            "type": "HTTP",
            "path": "/",
            "pathOperator": "STARTS_WITH"
          }
        ],
        "request": [
          {
            "name": "AI - Prompt Guard Rails",
            "enabled": true,
            "policy": "ai-prompt-guard-rails",
            "configuration":
              {
                  "resourceName": "ai-model-text-classification-resource",
                  "promptLocation": "{#request.jsonContent.prompt}",
                  "sensitivityThreshold": 0.1,
                  "contentChecks": "identity_hate,insult,obscene,severe_toxic,threat,toxic",
                  "requestPolicy": "BLOCK_REQUEST"
              }
          }
        ]
      }
    ]
  }
}

```


## Changelog

### 1.0.0 (2025-06-19)


##### Features

* implementation of AI prompt guard rails policy ([9f91cdd](https://github.com/gravitee-io/gravitee-policy-ai-prompt-guard-rails/commit/9f91cdd7d1a61b6253b2b8f88b9470994b2bf010))

