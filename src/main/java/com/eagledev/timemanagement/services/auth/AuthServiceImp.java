package com.eagledev.timemanagement.services.auth;

import com.eagledev.timemanagement.entities.User;
import com.eagledev.timemanagement.entities.enums.Role;
import com.eagledev.timemanagement.exceptions.UserNotFoundException;
import com.eagledev.timemanagement.models.auth.AuthRequest;
import com.eagledev.timemanagement.models.auth.JwtResponse;
import com.eagledev.timemanagement.models.user.UserCreationRequest;
import com.eagledev.timemanagement.repos.UserRepo;
import com.eagledev.timemanagement.config.JwtService;
import com.eagledev.timemanagement.services.email.EmailService;
import com.eagledev.timemanagement.services.verification.VerificationCodeService;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.apache.coyote.BadRequestException;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.MessageFormat;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class AuthServiceImp implements AuthService {
    private final UserRepo userRepo;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final EmailService emailService;
    private final VerificationCodeService verificationCodeService;

    @SneakyThrows
    @Transactional
    @Override
    public String registerUser(UserCreationRequest userDtoRequest) {
        if(userRepo.existsUserByUserName(userDtoRequest.getUserName())){
            throw new BadRequestException("username is already exist");
        }

        if(userRepo.existsUserByEmail(userDtoRequest.getEmail())){
            throw new BadRequestException("email is already exist");
        }

        User user = User.builder()
                .firstName(userDtoRequest.getFirstName())
                .lastName(userDtoRequest.getLastName())
                .userName(userDtoRequest.getUserName())
                .password(passwordEncoder.encode(userDtoRequest.getPassword()))
                .email(userDtoRequest.getEmail())
                .dateJoined(LocalDateTime.now())
//                .profilePictureUrl("TODO")
                .verified(false)
                .role(Role.USER)
                .build();
        userRepo.save(user);
        String verificationCode = verificationCodeService.generateVerificationCode(user);
        sendEmail(user , verificationCode);
        return "Registration successful. Please check your email for verification Code.";
    }

    @SneakyThrows
    @Override
    public String verifyUser(String userNameOrEmail , String code) {
        User user = userRepo.findUserByUserNameOrEmail(userNameOrEmail , userNameOrEmail)
                .orElseThrow(() -> new UserNotFoundException("USER IS NOT FOUND"));
        if(user.isVerified()){
            throw new BadRequestException("USER IS ALREADY VERIFIED !");
        }
        verificationCodeService.validateVerificationCode(code , userNameOrEmail);
        user.setVerified(true);
        userRepo.save(user);
        verificationCodeService.delete(code);
        return "Registration confirmed. You can now log in.";
    }

    @Transactional
    @Override
    public String sendVerificationCode(String userNameOrEmail) {
        User user = userRepo.findUserByUserNameOrEmail(userNameOrEmail, userNameOrEmail)
                .orElseThrow(() -> new UsernameNotFoundException("USER NOT FOUND"));
        String verificationCode = verificationCodeService.generateVerificationCode(user);
        sendEmail(user , verificationCode);
        return "Verification Email. Please check your email for verification Code.";
    }

    @SneakyThrows
    @Override
    public String resetPassword(String userNameOrEmail, String code ,String newPassword) {
        User user = userRepo.findUserByUserNameOrEmail(userNameOrEmail, userNameOrEmail)
                .orElseThrow(() -> new UsernameNotFoundException("USER NOT FOUND"));
        user.setPassword(passwordEncoder.encode(newPassword));
        verificationCodeService.validateVerificationCode(code , userNameOrEmail);
        verificationCodeService.delete(code);
        userRepo.save(user);
        return "password reset successfully";
    }

    @Override
    public JwtResponse authenticate(AuthRequest request) {
        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.getUsernameOrEmail() , request.getPassword())
        );
        User user = userRepo.findUserByUserNameOrEmail(request.getUsernameOrEmail() , request.getUsernameOrEmail())
                .orElseThrow(() -> new UsernameNotFoundException("USER IS NOT FOUND"));
        if(!user.isVerified()){
            throw new UserNotFoundException("User is not verified" , HttpStatus.FORBIDDEN.value());
        }
        return JwtResponse
                .builder()
                .token(jwtService.generateToken(user))
                .build();
    }

    @Override
    public boolean verifyCode(String code, String email) {
        return verificationCodeService.validateVerificationCode(code , email);
    }

    private void sendEmail(User user , String verificationCode){
        String VERIFICATION_EMAIL =   "<div style='font-family: Arial, sans-serif; width: 80%; margin: auto; padding: 20px; border: 1px solid #ddd; border-radius: 5px;'>"
                + "<div style='text-align: center; padding: 10px; background-color: #f8f8f8; border-bottom: 1px solid #ddd;'>"
                + "<h1>Welcome to TODO</h1>"
                + "</div>"
                + "<div style='padding: 20px;'>"
                + "<p>Dear, {0}</p>"
                + "<p>Thank you for registering at ToDo. Please use one time code to verify your email:</p>"
                + "<p>{1}</p>"
                + "<p>If you did not register at ToDo, please ignore this email.</p>"
                + "<p>Best Regards,</p>"
                + "</div>"
                + "</div>";
        emailService.send(user.getEmail(),"Verification Email", MessageFormat.format(VERIFICATION_EMAIL , user.getUsername() , verificationCode) );
    }
}
