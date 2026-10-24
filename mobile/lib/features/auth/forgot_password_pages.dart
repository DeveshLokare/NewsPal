import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../app/router.dart';
import '../../core/api_error.dart';
import 'auth_form.dart';
import 'password_reset_controller.dart';

/// Forgot password, step 1: the email (US-13).
class ForgotPasswordPage extends ConsumerStatefulWidget {
  const ForgotPasswordPage({super.key});

  @override
  ConsumerState<ForgotPasswordPage> createState() => _ForgotPasswordPageState();
}

class _ForgotPasswordPageState extends ConsumerState<ForgotPasswordPage> {
  final _formKey = GlobalKey<FormState>();
  late final _email = TextEditingController(text: ref.read(passwordResetControllerProvider)?.email ?? '');
  bool _busy = false;
  String? _error;

  @override
  void dispose() {
    _email.dispose();
    super.dispose();
  }

  Future<void> _continue() async {
    if (!_formKey.currentState!.validate()) return;
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      await ref.read(passwordResetControllerProvider.notifier).findQuestion(_email.text);
      if (mounted) await context.push(Routes.forgotPasswordAnswer);
    } on ApiError catch (e) {
      if (mounted) setState(() => _error = resetErrorMessage(e));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return AuthScaffold(
      title: 'Forgot your password?',
      subtitle: "Enter your email and answer your security question to set a new one.",
      children: [
        Form(
          key: _formKey,
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              TextFormField(
                controller: _email,
                decoration: const InputDecoration(labelText: 'Email', prefixIcon: Icon(Icons.mail_outline)),
                keyboardType: TextInputType.emailAddress,
                autofillHints: const [AutofillHints.email],
                textInputAction: TextInputAction.done,
                onFieldSubmitted: (_) => _continue(),
                validator: AuthValidators.email,
              ),
              const SizedBox(height: 12),
              FormErrorText(_error),
              SubmitButton(label: 'Continue', busy: _busy, onPressed: _continue),
            ],
          ),
        ),
        const SizedBox(height: 16),
        Center(
          child: TextButton(
            onPressed: _busy ? null : () => _backToLogin(context, ref),
            child: const Text('Back to log in'),
          ),
        ),
      ],
    );
  }
}

/// Forgot password, step 2: the answer and a new password (US-13, US-14).
class ResetPasswordPage extends ConsumerStatefulWidget {
  const ResetPasswordPage({super.key});

  @override
  ConsumerState<ResetPasswordPage> createState() => _ResetPasswordPageState();
}

class _ResetPasswordPageState extends ConsumerState<ResetPasswordPage> {
  final _formKey = GlobalKey<FormState>();
  final _answer = TextEditingController();
  final _password = TextEditingController();
  final _confirm = TextEditingController();
  bool _busy = false;
  bool _obscure = true;
  bool _locked = false;
  String? _error;

  @override
  void dispose() {
    _answer.dispose();
    _password.dispose();
    _confirm.dispose();
    super.dispose();
  }

  Future<void> _reset() async {
    if (!_formKey.currentState!.validate()) return;
    setState(() {
      _busy = true;
      _error = null;
    });
    final messenger = ScaffoldMessenger.of(context);
    try {
      await ref
          .read(passwordResetControllerProvider.notifier)
          .reset(answer: _answer.text, newPassword: _password.text);
      // Signed in now: the router moves on to the feed (or onboarding).
      messenger.showSnackBar(const SnackBar(content: Text('Password updated')));
    } on ApiError catch (e) {
      if (!mounted) return;
      setState(() {
        _error = resetErrorMessage(e);
        _locked = e.code == 'RESET_LOCKED' || e.code == 'RESET_RATE_LIMITED';
      });
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final question = ref.watch(passwordResetControllerProvider)?.question;
    if (question == null) {
      // Opened without step 1 (e.g. after a restart): start over.
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted) context.go(Routes.forgotPassword);
      });
      return const Scaffold(body: SizedBox.shrink());
    }
    final theme = Theme.of(context);
    return AuthScaffold(
      title: 'Answer your security question',
      subtitle: 'Then choose a new password.',
      children: [
        Form(
          key: _formKey,
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Text(question.text,
                  key: const ValueKey('reset-question'),
                  style: theme.textTheme.titleMedium?.copyWith(fontWeight: FontWeight.w600)),
              const SizedBox(height: 16),
              TextFormField(
                controller: _answer,
                decoration: const InputDecoration(
                  labelText: 'Answer',
                  helperText: "Capitals, spaces and punctuation don't matter",
                  prefixIcon: Icon(Icons.key_outlined),
                ),
                autocorrect: false,
                enableSuggestions: false,
                textInputAction: TextInputAction.next,
                validator: (v) => AuthValidators.required(v?.trim(), 'Enter your answer'),
              ),
              const SizedBox(height: 16),
              TextFormField(
                controller: _password,
                decoration: InputDecoration(
                  labelText: 'New password',
                  helperText: 'At least 8 characters',
                  prefixIcon: const Icon(Icons.lock_outline),
                  suffixIcon: IconButton(
                    tooltip: _obscure ? 'Show password' : 'Hide password',
                    icon: Icon(_obscure ? Icons.visibility_outlined : Icons.visibility_off_outlined),
                    onPressed: () => setState(() => _obscure = !_obscure),
                  ),
                ),
                obscureText: _obscure,
                autofillHints: const [AutofillHints.newPassword],
                textInputAction: TextInputAction.next,
                validator: AuthValidators.newPassword,
              ),
              const SizedBox(height: 16),
              TextFormField(
                controller: _confirm,
                decoration: const InputDecoration(labelText: 'Confirm new password', prefixIcon: Icon(Icons.lock_outline)),
                obscureText: _obscure,
                textInputAction: TextInputAction.done,
                onFieldSubmitted: (_) => _reset(),
                validator: (v) => v != _password.text ? "Passwords don't match" : null,
              ),
              const SizedBox(height: 12),
              FormErrorText(_error),
              SubmitButton(label: 'Reset password', busy: _busy, onPressed: _locked ? null : _reset),
            ],
          ),
        ),
        const SizedBox(height: 16),
        Center(
          child: TextButton(
            onPressed: _busy ? null : () => _backToLogin(context, ref),
            child: const Text('Back to log in'),
          ),
        ),
      ],
    );
  }
}

void _backToLogin(BuildContext context, WidgetRef ref) {
  ref.read(passwordResetControllerProvider.notifier).clear();
  context.go(Routes.login);
}
