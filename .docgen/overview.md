This policy uses an AI-powered text classification model to evaluate user prompts for potentially inappropriate or malicious content. It is capable of detecting a wide range of violations, such as profanity, sexually explicit language, harmful intent, and jailbreak prompt injections — adversarial inputs crafted to bypass AI safety mechanisms.

Depending on configuration, when a prompt is flagged:

* **Blocked and flagged** – the request is denied at the gateway
* **Allowed but flagged** – the request proceeds but is logged for monitoring

>**_NOTE_**: You may face an error when using this policy using the Gravitee's docker image. This is due to the fact that the default image are based on Alpine Linux, which does not support the ONNX Runtime. To resolve this issue you need to use the Gravitee's docker image based on Debian, which is available at `graviteeio/apim-gateway:4.8.0-debian`.

## Content Checks

The Content Checks property specifies the classification labels that will be applied to evaluate prompts. Labels should be chosen in alignment with the selected model's capabilities and the intended filtering goals—for instance, filtering for profanity while omitting toxicity checks.

Supported labels are typically documented in the model’s card or configuration file.


## AI Model Resource

The policy requires an **AI Model Text Classification Resource** to be defined at the API level. This resource serves as the classification engine for evaluating prompt content during policy execution.

Details on creating and managing such resources can be found at: https://documentation.gravitee.io/apim/policies/resources

NOTE: After the resource is created, the policy must be configured with the corresponding name using the **AI Model Resource Name** property.


## Notice

This plugin exposes models based on meta LLama4:

* link:https://huggingface.co/gravitee-io/Llama-Prompt-Guard-2-22M-onnx[gravitee-io/Llama-Prompt-Guard-2-22M-onxx]
* link:https://huggingface.co/gravitee-io/Llama-Prompt-Guard-2-86M-onnx[gravitee-io/Llama-Prompt-Guard-2-86M-onxx]

> Llama 4 is licensed under the Llama 4 Community License, Copyright © Meta Platforms, Inc. All Rights Reserved.
